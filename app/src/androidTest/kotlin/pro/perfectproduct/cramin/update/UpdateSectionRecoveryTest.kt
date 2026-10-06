package pro.perfectproduct.cramin.update

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import pro.perfectproduct.cramin.R
import pro.perfectproduct.cramin.app.AppContainer
import pro.perfectproduct.cramin.app.LocalContainer
import pro.perfectproduct.cramin.app.theme.CraminTheme
import pro.perfectproduct.cramin.ui.update.UpdateSection
import pro.perfectproduct.cramin.util.Hashing
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Offline CI UI contract; real signed APK + PackageInstaller acceptance runs separately. */
class UpdateSectionRecoveryTest {
    private val compose = createComposeRule()
    private val context: android.content.Context = ApplicationProvider.getApplicationContext()
    private val dir = File(context.cacheDir, "updater-ui-${System.nanoTime()}").apply { mkdirs() }
    private val pending = PendingUpdateStore(File(dir, "pending.json"), File(dir, "updates"))
    private val events = MutableSharedFlow<InstallResult>(extraBufferCapacity = 8)
    private val checkRequests = AtomicInteger()
    private val downloadRequests = AtomicInteger()
    private var checkCode = 503
    private var checksumCode = 503
    private val bytes = "synthetic APK".toByteArray()
    private val installer = object : UpdateInstaller {
        var installs = 0
        override fun canInstall() = true
        override fun validate(apk: File) = apk.isFile
        override suspend fun install(apk: File) { installs++; pending.transition(PendingUpdateStore.Phase.SUBMITTED, installs) }
    }
    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        val path = chain.request().url.encodedPath
        val code = when (path) { "/latest" -> { checkRequests.incrementAndGet(); checkCode }; "/sha" -> { downloadRequests.incrementAndGet(); checksumCode }; else -> 200 }
        val body = when (path) {
            "/latest" -> """{"tag_name":"v0.1.49","assets":[{"name":"cramin-v0.1.49.apk","browser_download_url":"https://fixture.test/apk"},{"name":"cramin-v0.1.49.apk.sha256","browser_download_url":"https://fixture.test/sha"}]}"""
            "/sha" -> Hashing.sha256Hex(bytes)
            else -> "synthetic APK"
        }
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(body.toResponseBody()).build()
    }.build()
    private val container = object : AppContainer(context) {
        override val updateManager by lazy { UpdateManager(UpdateChecker(http, 48, "https://fixture.test/latest"), ApkDownloader(http, dir), installer, appScope, pending, events) }
    }
    @get:Rule val resources: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun after() { container.appScope.cancel(); dir.deleteRecursively() }
    }).around(compose)
    private fun render() = compose.setContent { CompositionLocalProvider(LocalContainer provides container) { CraminTheme { Surface { Column { UpdateSection() } } } } }
    private fun awaitText(id: Int) { compose.waitUntil(5_000) { compose.onAllNodesWithText(context.getString(id)).fetchSemanticsNodes().isNotEmpty() } }

    @Test fun checkErrorShowsUserTextAndRetryActuallyChecks() {
        render()
        compose.onNodeWithTag("checkUpdates").performClick()
        awaitText(R.string.update_check_error)
        compose.onNodeWithText("HTTP 503", substring = true).assertDoesNotExist()
        checkCode = 200
        compose.onNodeWithText(context.getString(R.string.action_retry)).performClick()
        compose.waitUntil(5_000) { container.updateManager.state.value is UpdateUi.Available }
        assertEquals(2, checkRequests.get())
        assertEquals(0, installer.installs)
    }

    @Test fun downloadRetryAndCancelledInstallerRequireExplicitClick() {
        checkCode = 200; render()
        compose.onNodeWithTag("checkUpdates").performClick()
        compose.waitUntil(5_000) { container.updateManager.state.value is UpdateUi.Available }
        compose.onNodeWithText(context.getString(R.string.update_install)).performClick()
        awaitText(R.string.update_download_error)
        compose.onNodeWithText("no checksum", substring = true).assertDoesNotExist()
        checksumCode = 200
        compose.onNodeWithText(context.getString(R.string.action_retry)).performClick()
        compose.waitUntil(5_000) { pending.loadEntry()?.sessionId == 1 }
        assertEquals(1, checkRequests.get()); assertEquals(2, downloadRequests.get())
        assertTrue(pending.completeSession(1, success = false, cancelled = true))
        runBlocking { events.emit(InstallResult.Cancelled(1)) }
        awaitText(R.string.update_install_cancelled)
        repeat(3) { container.updateManager.resumePending(); compose.waitForIdle() }
        assertEquals(1, installer.installs)
        compose.onNodeWithText(context.getString(R.string.action_retry)).performClick()
        compose.waitUntil(5_000) { pending.loadEntry()?.sessionId == 2 }
        assertEquals(2, installer.installs)
    }
}
