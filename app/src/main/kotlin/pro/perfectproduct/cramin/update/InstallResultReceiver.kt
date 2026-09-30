package pro.perfectproduct.cramin.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Принимает результат сессии PackageInstaller (SPEC §12.4 п. 6).
 * Реализация — в фазе 6; объявлен в src/update/AndroidManifest.xml.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
