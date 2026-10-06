package pro.perfectproduct.cramin.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import pro.perfectproduct.cramin.util.Log
import java.io.File

/** Результат установки, доставленный [InstallResultReceiver]. */
sealed interface InstallResult {
    data class Pending(val sessionId: Int) : InstallResult
    data class Success(val sessionId: Int) : InstallResult
    data class Cancelled(val sessionId: Int) : InstallResult
    data class Failure(val sessionId: Int, val status: Int) : InstallResult
}

/**
 * Установка через PackageInstaller session API (SPEC §12.4 п. 5–6). Без разрешения на установку
 * из этого источника открываются системные настройки; результат приходит в [InstallResultReceiver].
 */
interface UpdateInstaller {
    fun canInstall(): Boolean
    fun validate(apk: File): Boolean
    suspend fun install(apk: File)
    fun abandonSession(sessionId: Int) {}
}

class ApkInstaller(private val context: Context, private val pendingStore: PendingUpdateStore = PendingUpdateStore.forContext(context)) : UpdateInstaller {

    override fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    @Suppress("DEPRECATION")
    override fun validate(apk: File): Boolean = runCatching {
        val pm = context.packageManager
        val flags = android.content.pm.PackageManager.GET_SIGNATURES
        val candidate = pm.getPackageArchiveInfo(apk.path, flags) ?: return false
        val current = pm.getPackageInfo(context.packageName, flags)
        fun version(info: android.content.pm.PackageInfo): Long =
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        fun signatures(info: android.content.pm.PackageInfo) = info.signatures.orEmpty()
            .map { pro.perfectproduct.cramin.util.Hashing.sha256Hex(it.toByteArray()) }.toSet()
        candidate.packageName == context.packageName && version(candidate) > version(current) &&
            signatures(candidate).isNotEmpty() && signatures(candidate) == signatures(current)
    }.getOrDefault(false)

    fun unknownSourcesIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    override suspend fun install(apk: File): Unit = withContext(Dispatchers.IO) {
        require(validate(apk)) { "invalid update package" }
        currentCoroutineContext().ensureActive()
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        try {
            pendingStore.transition(PendingUpdateStore.Phase.SUBMITTED, sessionId)
            installer.openSession(sessionId).use { session ->
                session.openWrite("cramin.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                currentCoroutineContext().ensureActive()
                val intent = Intent(context, InstallResultReceiver::class.java).setAction(ACTION_RESULT).putExtra(EXTRA_SESSION_ID, sessionId)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            abandonSession(sessionId)
            throw e
        }
        Log.i(TAG, "install session $sessionId committed")
    }

    override fun abandonSession(sessionId: Int) {
        runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
    }

    companion object {
        private const val TAG = "Update"
        const val ACTION_RESULT = "pro.perfectproduct.cramin.update.INSTALL_RESULT"

        /** Общий канал результата: ресивер объявлен в манифесте, а UI подписан через Flow. */
        const val EXTRA_SESSION_ID = "pro.perfectproduct.cramin.update.SESSION_ID"
        // No replay of a stale cancellation into a newly constructed manager.
        val results = MutableSharedFlow<InstallResult>(extraBufferCapacity = 8)
        val resultFlow: SharedFlow<InstallResult> get() = results
    }
}

/** Принимает результат сессии PackageInstaller (SPEC §12.4 п. 6); объявлен в src/update/AndroidManifest.xml. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val sessionId = intent.getIntExtra(ApkInstaller.EXTRA_SESSION_ID, -1)
        val store = PendingUpdateStore.forContext(context)
        if (!store.matchesSession(sessionId)) return
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Система просит подтверждение: показываем её диалог.
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                // NEW_TASK alone may bring a confirmation for an abandoned session back to
                // the foreground after process recovery. Each new session needs its own task.
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                    ?.let { runCatching { context.startActivity(it) } }
                ApkInstaller.results.tryEmit(InstallResult.Pending(sessionId))
            }
            PackageInstaller.STATUS_SUCCESS -> {
                if (store.completeSession(sessionId, success = true)) ApkInstaller.results.tryEmit(InstallResult.Success(sessionId))
            }
            else -> {
                val cancelled = status == PackageInstaller.STATUS_FAILURE_ABORTED
                // Persist BEFORE Activity resumes, including when its old process is gone.
                if (store.completeSession(sessionId, success = false, cancelled = cancelled)) {
                    ApkInstaller.results.tryEmit(if (cancelled) InstallResult.Cancelled(sessionId) else InstallResult.Failure(sessionId, status))
                }
            }
        }
        Log.i("Update", "install result status=$status")
    }
}
