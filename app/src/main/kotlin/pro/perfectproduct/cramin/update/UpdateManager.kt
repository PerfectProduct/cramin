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
    private val installer: ApkInstaller,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<UpdateUi>(UpdateUi.Idle)
    val state: StateFlow<UpdateUi> = _state
    private var job: Job? = null

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

    /** После возврата из системных настроек установка продолжается (SPEC §12.4 п. 5). */
    fun install(apk: File) {
        if (!installer.canInstall()) {
            _state.value = UpdateUi.NeedsPermission(apk)
            return
        }
        scope.launch {
            _state.value = UpdateUi.Installing(apk)
            runCatching { installer.install(apk) }.onFailure { _state.value = UpdateUi.Error(it.javaClass.simpleName) }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = UpdateUi.Idle
    }

    fun reset() {
        _state.value = UpdateUi.Idle
    }
}
