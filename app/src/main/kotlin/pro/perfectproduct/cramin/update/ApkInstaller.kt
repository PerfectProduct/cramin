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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import pro.perfectproduct.cramin.util.Log
import java.io.File

/** Результат установки, доставленный [InstallResultReceiver]. */
sealed interface InstallResult {
    data object Pending : InstallResult
    data object Success : InstallResult
    data class Failure(val message: String) : InstallResult
}

/**
 * Установка через PackageInstaller session API (SPEC §12.4 п. 5–6). Без разрешения на установку
 * из этого источника открываются системные настройки; результат приходит в [InstallResultReceiver].
 */
interface UpdateInstaller {
    fun canInstall(): Boolean
    fun validate(apk: File): Boolean
    suspend fun install(apk: File)
}

class ApkInstaller(private val context: Context) : UpdateInstaller {

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
        results.value = InstallResult.Pending
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("cramin.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallResultReceiver::class.java).setAction(ACTION_RESULT)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
        Log.i(TAG, "install session $sessionId committed")
    }

    companion object {
        private const val TAG = "Update"
        const val ACTION_RESULT = "pro.perfectproduct.cramin.update.INSTALL_RESULT"

        /** Общий канал результата: ресивер объявлен в манифесте, а UI подписан через Flow. */
        val results = MutableStateFlow<InstallResult?>(null)
        val resultFlow: StateFlow<InstallResult?> get() = results
    }
}

/** Принимает результат сессии PackageInstaller (SPEC §12.4 п. 6); объявлен в src/update/AndroidManifest.xml. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Система просит подтверждение: показываем её диалог.
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) } }
                ApkInstaller.results.value = InstallResult.Pending
            }
            PackageInstaller.STATUS_SUCCESS -> ApkInstaller.results.value = InstallResult.Success
            else -> ApkInstaller.results.value = InstallResult.Failure("status $status")
        }
        Log.i("Update", "install result status=$status")
    }
}
