package pro.perfectproduct.cramin.update

import android.app.Application
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class InstallResultReceiverTest {
    private lateinit var app: Application
    private lateinit var store: PendingUpdateStore

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        store = PendingUpdateStore.forContext(app)
        store.clear()
        val apk = File(app.cacheDir, "updates/fixture.apk").apply {
            parentFile.mkdirs()
            writeText("verified receiver fixture")
        }
        store.save(apk)
        store.transition(PendingUpdateStore.Phase.SUBMITTED, 42)
    }

    private fun result(status: Int) = Intent(ApkInstaller.ACTION_RESULT)
        .putExtra(ApkInstaller.EXTRA_SESSION_ID, 42)
        .putExtra(PackageInstaller.EXTRA_STATUS, status)

    @Test fun confirmationUsesNewTaskForItsOwnSessionRatherThanOldInstallerTask() {
        val confirm = Intent("native.fixture.CONFIRM")
            .putExtra(PackageInstaller.EXTRA_SESSION_ID, 42)
            .addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        InstallResultReceiver().onReceive(app, result(PackageInstaller.STATUS_PENDING_USER_ACTION)
            .putExtra(Intent.EXTRA_INTENT, confirm))
        val started = shadowOf(app).nextStartedActivity
        assertNotNull(started)
        assertEquals(42, started.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1))
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, started.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        assertEquals(Intent.FLAG_ACTIVITY_MULTIPLE_TASK, started.flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        assertEquals(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS, started.flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
    }

    @Test fun cancellationPersistsBeforeRecoveryAndIgnoresLateConfirmation() {
        val receiver = InstallResultReceiver()
        receiver.onReceive(app, result(PackageInstaller.STATUS_FAILURE_ABORTED))
        assertEquals(PendingUpdateStore.Phase.CANCELLED, store.loadEntry()?.phase)
        receiver.onReceive(app, result(PackageInstaller.STATUS_PENDING_USER_ACTION)
            .putExtra(Intent.EXTRA_INTENT, Intent("native.fixture.CONFIRM")))
        assertNull(shadowOf(app).nextStartedActivity)
    }
}
