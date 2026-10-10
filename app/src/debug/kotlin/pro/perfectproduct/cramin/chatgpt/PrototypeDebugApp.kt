package pro.perfectproduct.cramin.chatgpt

import android.app.ActivityManager
import android.os.Build
import android.os.Process
import pro.perfectproduct.cramin.app.CraminApp

/** The separate sign-in process never constructs AppContainer or resumes document jobs. */
class PrototypeDebugApp : CraminApp() {
    override fun onCreate() {
        val name = if (Build.VERSION.SDK_INT >= 28) getProcessName() else {
            (getSystemService(ACTIVITY_SERVICE) as ActivityManager).runningAppProcesses
                ?.firstOrNull { it.pid == Process.myPid() }?.processName
        }
        check(name != null) { "process_identity_unavailable" }
        // Application.onCreate() is empty. Calling CraminApp.onCreate() here would recover document jobs.
        if (name.endsWith(":chatgptPrototype")) return
        super.onCreate()
    }
}
