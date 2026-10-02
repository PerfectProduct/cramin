package pro.perfectproduct.cramin.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Состояние сценария обновления для экрана настроек (SPEC U8, §12.4). */
sealed interface UpdateUi {
    data object Idle : UpdateUi
    data object Checking : UpdateUi
    data class UpToDate(val versionCode: Int) : UpdateUi
    data class Available(val release: ReleaseInfo) : UpdateUi
    data class Downloading(val release: ReleaseInfo, val done: Long, val total: Long) : UpdateUi
    data class NeedsPermission(val apk: File) : UpdateUi
    data class Installing(val apk: File) : UpdateUi
    data class Error(val message: String, val checksum: Boolean = false) : UpdateUi
}

class UpdateManager(
    private val checker: UpdateChecker,
    private val downloader: ApkDownloader,
    private val installer: UpdateInstaller,
    private val scope: CoroutineScope,
    private val pending: PendingUpdateStore? = null,
) {
    private val _state = MutableStateFlow<UpdateUi>(UpdateUi.Idle)
    val state: StateFlow<UpdateUi> = _state
    private var job: Job? = null
    private var installJob: Job? = null

    init {
        scope.launch {
            ApkInstaller.resultFlow.collect { result ->
                when (result) {
                    InstallResult.Success -> { pending?.clear(); _state.value = UpdateUi.Idle }
                    is InstallResult.Failure -> _state.value = UpdateUi.Error(result.message)
                    else -> Unit
                }
            }
        }
    }

    /** Called after Settings returns, and whenever the update UI is recreated/resumed. */
    fun resumePending() {
        if (installJob?.isActive == true || _state.value is UpdateUi.Installing) return
        installJob = scope.launch {
            val apk = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { pending?.load() }
                ?: return@launch
            installPrepared(apk, save = false)
        }
    }

    fun check() {
        job?.cancel()
        job = scope.launch {
            _state.value = UpdateUi.Checking
            _state.value = when (val r = checker.check()) {
                is UpdateCheck.UpToDate -> UpdateUi.UpToDate(r.current)
                is UpdateCheck.Available -> UpdateUi.Available(r.release)
                is UpdateCheck.Error -> UpdateUi.Error(r.detail)
            }
        }
    }

    fun downloadAndInstall(release: ReleaseInfo) {
        job?.cancel()
        job = scope.launch {
            _state.value = UpdateUi.Downloading(release, 0, release.apkSize)
            val apk = try {
                downloader.download(release) { done, total -> _state.value = UpdateUi.Downloading(release, done, total) }
            } catch (e: DownloadException) {
                _state.value = UpdateUi.Error(e.message.orEmpty(), checksum = e.checksumMismatch)
                return@launch
            }
            install(apk)
        }
    }

    fun install(apk: File) {
        if (installJob?.isActive == true) return
        installJob = scope.launch { installPrepared(apk, save = true) }
    }

    private suspend fun installPrepared(apk: File, save: Boolean) {
        try {
            val valid = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (!installer.validate(apk)) false else {
                    if (save) pending?.save(apk)
                    true
                }
            }
            if (!valid) {
                pending?.clear()
                _state.value = UpdateUi.Error("invalid update package")
                return
            }
            if (!installer.canInstall()) {
                _state.value = UpdateUi.NeedsPermission(apk)
                return
            }
            _state.value = UpdateUi.Installing(apk)
            installer.install(apk)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { _state.value = UpdateUi.Error(e.javaClass.simpleName) }
    }

    fun cancel() {
        job?.cancel()
        installJob?.cancel()
        pending?.clear()
        _state.value = UpdateUi.Idle
    }

    fun reset() {
        pending?.clear()
        _state.value = UpdateUi.Idle
    }
}
