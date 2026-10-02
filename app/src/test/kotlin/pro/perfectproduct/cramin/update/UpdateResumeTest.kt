package pro.perfectproduct.cramin.update

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UpdateResumeTest {
    @get:Rule val tmp = TemporaryFolder()
    private class Installer : UpdateInstaller {
        var allowed = false
        @Volatile var installs = 0
        override fun canInstall() = allowed
        override fun validate(apk: File) = apk.isFile
        override suspend fun install(apk: File) { installs++ }
    }

    @Test fun permissionReturnAndManagerRecreationResumeExactlyOnce() = runBlocking {
        val dir = tmp.newFolder("updates")
        val apk = File(dir, "synthetic.apk").apply { writeText("synthetic") }
        val record = File(tmp.root, "pending.json")
        val installer = Installer()
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        fun manager(scope: CoroutineScope) = UpdateManager(UpdateChecker(OkHttpClient(), 1), ApkDownloader(OkHttpClient(), tmp.root), installer, scope, PendingUpdateStore(record, dir))
        try {
            val first = manager(firstScope)
            first.install(apk)
            withTimeout(5_000) { first.state.first { it is UpdateUi.NeedsPermission } }
            assertEquals(0, installer.installs)
            assertTrue(record.isFile)
            firstScope.cancel()
            val recreated = manager(secondScope)
            recreated.resumePending() // denial is still recoverable
            withTimeout(5_000) { recreated.state.first { it is UpdateUi.NeedsPermission } }
            installer.allowed = true
            recreated.resumePending() // ActivityResult/ON_RESUME route
            withTimeout(5_000) { while (installer.installs != 1) delay(10) }
            recreated.resumePending()
            delay(100)
            assertEquals(1, installer.installs)
        } finally { firstScope.cancel(); secondScope.cancel() }
    }
}
