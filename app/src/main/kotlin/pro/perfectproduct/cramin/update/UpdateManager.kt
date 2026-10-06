package pro.perfectproduct.cramin.update

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import pro.perfectproduct.cramin.util.Log
import java.io.File

/** Diagnostics stay outside user-facing text. No URLs, credentials or provider bodies. */
enum class UpdateFailure { CHECK, DOWNLOAD, INTEGRITY, INSTALL, CANCELLED, INTERRUPTED }
sealed interface UpdateUi {
    data object Idle : UpdateUi
    data object Checking : UpdateUi
    data class UpToDate(val versionCode: Int) : UpdateUi
    data class Available(val release: ReleaseInfo) : UpdateUi
    data class Downloading(val release: ReleaseInfo, val done: Long, val total: Long) : UpdateUi
    data class NeedsPermission(val apk: File) : UpdateUi
    data class Installing(val apk: File) : UpdateUi
    data class Error(val kind: UpdateFailure, val diagnostic: String) : UpdateUi
}

class UpdateManager(
    private val checker: UpdateChecker,
    private val downloader: ApkDownloader,
    private val installer: UpdateInstaller,
    private val scope: CoroutineScope,
    private val pending: PendingUpdateStore? = null,
    results: Flow<InstallResult> = ApkInstaller.resultFlow,
) {
    private sealed interface RetryAction {
        data object Check : RetryAction
        data class Download(val release: ReleaseInfo) : RetryAction
        data object Install : RetryAction
    }
    private val _state = MutableStateFlow<UpdateUi>(UpdateUi.Idle)
    val state: StateFlow<UpdateUi> = _state
    private val operationLock = Any()
    private var operation: Job? = null
    private var queuedRetry = false
    private var queuedPermissionResume = false
    private var retryAction: RetryAction = RetryAction.Check

    init {
        scope.launch {
            results.collect { result -> applyInstallResult(result) }
        }
    }

    /** A receiver-approved event can still be stale by the time this IO collector sees it. */
    private fun applyInstallResult(result: InstallResult): Unit = synchronized(operationLock) {
        val store = pending ?: return
        when (result) {
            is InstallResult.Success -> store.applyTerminalResult(result.sessionId, PendingUpdateStore.Phase.SUCCEEDED) {
                _state.value = UpdateUi.Idle
            }
            is InstallResult.Cancelled -> store.applyTerminalResult(result.sessionId, PendingUpdateStore.Phase.CANCELLED) {
                error(UpdateFailure.CANCELLED, "installer cancelled", RetryAction.Install)
            }
            is InstallResult.Failure -> store.applyTerminalResult(result.sessionId, PendingUpdateStore.Phase.FAILED) {
                error(UpdateFailure.INSTALL, "installer status ${result.status}", RetryAction.Install)
            }
            is InstallResult.Pending -> Unit
        }
        Unit
    }

    /** A single operation slot covers check, download, permission recovery and submission. */
    private fun launchOperation(block: suspend () -> Unit): Unit = synchronized(operationLock) {
        if (operation?.isCompleted == false || _state.value is UpdateUi.Installing) return
        val task = scope.launch(start = CoroutineStart.LAZY) { block() }
        operation = task
        task.invokeOnCompletion {
            synchronized(operationLock) {
                if (operation === task) {
                    operation = null
                    val shouldRetry = queuedRetry
                    val shouldResume = queuedPermissionResume
                    queuedRetry = false
                    queuedPermissionResume = false
                    if (shouldRetry && (_state.value is UpdateUi.Error || _state.value is UpdateUi.NeedsPermission)) retry()
                    else if (shouldResume && _state.value is UpdateUi.NeedsPermission) resumePending()
                }
            }
        }
        task.start()
    }

    private fun error(kind: UpdateFailure, detail: String, action: RetryAction) {
        retryAction = action
        Log.w("Update", "$kind: $detail")
        _state.value = UpdateUi.Error(kind, detail)
    }

    /** Only pre-submission/permission intent is automatic; cancellation/failure never is. */
    fun resumePending(): Unit = synchronized(operationLock) {
        if (operation?.isCompleted == false && _state.value is UpdateUi.NeedsPermission) {
            // ActivityResult/ON_RESUME can arrive before permission preparation completes.
            // Preserve one permission recheck rather than losing the grant or overlapping work.
            queuedPermissionResume = true
            return
        }
        launchOperation { resumePendingNow() }
    }

    private suspend fun resumePendingNow() {
        val entry = withContext(Dispatchers.IO) { pending?.loadEntry() } ?: return
        when (entry.phase) {
            PendingUpdateStore.Phase.PREPARED, PendingUpdateStore.Phase.PERMISSION -> installPrepared(entry.apk)
            PendingUpdateStore.Phase.CANCELLED -> error(UpdateFailure.CANCELLED, "saved cancellation", RetryAction.Install)
            PendingUpdateStore.Phase.FAILED -> error(UpdateFailure.INSTALL, "saved installer failure", RetryAction.Install)
            PendingUpdateStore.Phase.SUCCEEDED -> entry.sessionId?.let { applyInstallResult(InstallResult.Success(it)) }
            PendingUpdateStore.Phase.SUBMITTED, PendingUpdateStore.Phase.RETRY -> {
                // A new process must not create a duplicate of the existing installer session.
                // Leave it available for explicit retry (which abandons the previous session).
                error(UpdateFailure.INTERRUPTED, "saved ${entry.phase}", RetryAction.Install)
            }
        }
    }

    fun check() = launchOperation { checkNow() }
    private suspend fun checkNow() {
        _state.value = UpdateUi.Checking
        when (val result = checker.check()) {
            is UpdateCheck.UpToDate -> _state.value = UpdateUi.UpToDate(result.current)
            is UpdateCheck.Available -> _state.value = UpdateUi.Available(result.release)
            is UpdateCheck.Error -> error(UpdateFailure.CHECK, result.detail, RetryAction.Check)
        }
    }

    fun downloadAndInstall(release: ReleaseInfo) = launchOperation { downloadNow(release) }
    private suspend fun downloadNow(release: ReleaseInfo) {
        _state.value = UpdateUi.Downloading(release, 0, release.apkSize)
        try {
            val downloadJob = currentCoroutineContext().job
            val apk = downloader.download(release) { done, total ->
                synchronized(operationLock) {
                    if (downloadJob.isActive && operation === downloadJob) _state.value = UpdateUi.Downloading(release, done, total)
                }
            }
            currentCoroutineContext().ensureActive()
            withContext(Dispatchers.IO) { pending?.save(apk) }
            installPrepared(apk)
        } catch (e: CancellationException) { throw e }
        catch (e: DownloadException) {
            currentCoroutineContext().ensureActive()
            error(if (e.checksumMismatch) UpdateFailure.INTEGRITY else UpdateFailure.DOWNLOAD,
                e.message.orEmpty(), RetryAction.Download(release))
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            error(UpdateFailure.DOWNLOAD, e.javaClass.simpleName, RetryAction.Download(release))
        }
    }

    fun retry(): Unit = synchronized(operationLock) {
        if (_state.value !is UpdateUi.Error && _state.value !is UpdateUi.NeedsPermission) return
        if (operation?.isCompleted == false) {
            // Error is visible slightly before the coroutine completes. Retain ONE early tap,
            // and wait for cleanup instead of dropping it or overlapping another operation.
            queuedRetry = true
            return
        }
        launchOperation { retryNow() }
    }

    private suspend fun retryNow() {
        when (val action = retryAction) {
            RetryAction.Check -> checkNow()
            is RetryAction.Download -> downloadNow(action.release)
            RetryAction.Install -> {
                val entry = withContext(Dispatchers.IO) { pending?.loadEntry() }
                if (entry == null) {
                    error(UpdateFailure.INTEGRITY, "verified update missing", RetryAction.Check)
                } else {
                    withContext(Dispatchers.IO) {
                        // Invalidate the old callback identity before abandoning it.
                        pending?.transition(PendingUpdateStore.Phase.PREPARED)
                        entry.sessionId?.let { installer.abandonSession(it) }
                    }
                    installPrepared(entry.apk)
                }
            }
        }
    }

    fun install(apk: File) = launchOperation {
        withContext(Dispatchers.IO) { pending?.save(apk) }
        installPrepared(apk)
    }

    private suspend fun installPrepared(apk: File) {
        try {
            val valid = withContext(Dispatchers.IO) {
                (pending == null || pending.load() == apk) && installer.validate(apk)
            }
            if (!valid) {
                pending?.clear()
                error(UpdateFailure.INTEGRITY, "invalid update package", RetryAction.Check)
                return
            }
            retryAction = RetryAction.Install
            if (!installer.canInstall()) {
                withContext(Dispatchers.IO) { pending?.transition(PendingUpdateStore.Phase.PERMISSION) }
                _state.value = UpdateUi.NeedsPermission(apk)
                return
            }
            withContext(Dispatchers.IO) { pending?.transition(PendingUpdateStore.Phase.SUBMITTED) }
            _state.value = UpdateUi.Installing(apk)
            installer.install(apk)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            pausePrepared(PendingUpdateStore.Phase.FAILED)
            error(UpdateFailure.INSTALL, e.javaClass.simpleName, RetryAction.Install)
        }
    }

    private suspend fun pausePrepared(phase: PendingUpdateStore.Phase) = withContext(Dispatchers.IO) {
        pending?.loadEntry()?.let { pending.transition(phase, it.sessionId) }
    }

    fun cancel(): Unit = synchronized(operationLock) {
        if (_state.value is UpdateUi.Installing) return
        queuedRetry = false
        queuedPermissionResume = false
        _state.value = UpdateUi.Idle
        operation?.cancel()
        pending?.clear()
    }

    fun reset() = cancel()
}
