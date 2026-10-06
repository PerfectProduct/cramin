package pro.perfectproduct.cramin.update

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pro.perfectproduct.cramin.util.Hashing
import pro.perfectproduct.cramin.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

class UpdateRecoveryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private val scopes = mutableListOf<CoroutineScope>()
    private val http = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()
    private val events = MutableSharedFlow<InstallResult>(extraBufferCapacity = 8)
    private lateinit var store: PendingUpdateStore
    private lateinit var apk: File
    private class Installer(private val store: PendingUpdateStore) : UpdateInstaller {
        var allowed = true
        @Volatile var installs = 0
        val abandoned = mutableListOf<Int>()
        override fun canInstall() = allowed
        override fun validate(apk: File) = apk.isFile
        override suspend fun install(apk: File) {
            installs++
            store.transition(PendingUpdateStore.Phase.SUBMITTED, installs)
        }
        override fun abandonSession(sessionId: Int) { abandoned += sessionId }
    }
    private lateinit var installer: Installer
    @Before fun setup() {
        Log.install { _, _, _, _ -> }
        server.start()
        val dir = tmp.newFolder("updates")
        store = PendingUpdateStore(File(tmp.root, "pending.json"), dir)
        apk = File(dir, "fixture.apk").apply { writeText("verified synthetic APK") }
        installer = Installer(store)
    }
    @After fun cleanup() { scopes.forEach { it.cancel() }; server.close(); Log.install(null) }
    private fun manager(): UpdateManager {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        return UpdateManager(UpdateChecker(http, 48, server.url("/latest").toString()), ApkDownloader(http, tmp.root), installer, scope, store, events)
    }
    private suspend fun await(m: UpdateManager, predicate: (UpdateUi) -> Boolean) = withTimeout(5_000) { m.state.first(predicate) }
    private val release get() = ReleaseInfo("v0.1.49", 49, "0.1.49", "", server.url("/apk").toString(), "cramin-v0.1.49.apk", 500_000, server.url("/sha").toString())
    private fun json() = """{"tag_name":"v0.1.49","assets":[{"name":"cramin-v0.1.49.apk","browser_download_url":"${release.apkUrl}"},{"name":"cramin-v0.1.49.apk.sha256","browser_download_url":"${release.sha256Url}"}]}"""

    @Test fun checkFailureRetriesCheckWithoutPendingAndCoalescesClicks() = runBlocking {
        val m = manager()
        // Resume inline inside Error publication, BEFORE the failed operation completes.
        // This reproduces the lost Retry reported by push CI without sleeps or blind reruns.
        val immediateRetry = launch(Dispatchers.Unconfined) {
            val e = await(m) { it is UpdateUi.Error } as UpdateUi.Error
            assertEquals(UpdateFailure.CHECK, e.kind); assertEquals("HTTP 503", e.diagnostic)
            assertNull(store.load())
            server.enqueue(MockResponse.Builder().body(json()).headersDelay(150, TimeUnit.MILLISECONDS).build())
            repeat(10) { m.retry() }
        }
        server.enqueue(MockResponse(code = 503)); m.check()
        immediateRetry.join()
        await(m) { it is UpdateUi.Available }
        assertEquals(2, server.requestCount); assertEquals(0, installer.installs)
    }

    @Test fun downloadFailureRetriesDownloadWithoutPendingAndWithoutExtraCheck() = runBlocking {
        val m = manager(); val bytes = ByteArray(500_000) { (it % 251).toByte() }
        server.enqueue(MockResponse(code = 503)); m.downloadAndInstall(release)
        await(m) { it is UpdateUi.Error }; assertNull(store.load())
        server.enqueue(MockResponse(body = Hashing.sha256Hex(bytes)))
        server.enqueue(MockResponse.Builder().body(Buffer().write(bytes)).headersDelay(150, TimeUnit.MILLISECONDS).build())
        repeat(10) { m.retry() }
        await(m) { it is UpdateUi.Installing }
        withTimeout(5_000) { while (installer.installs != 1) delay(10) }
        assertEquals(3, server.requestCount); assertEquals(1, installer.installs)
        assertEquals("/sha", server.takeRequest().url.encodedPath)
        assertEquals("/sha", server.takeRequest().url.encodedPath)
        assertEquals("/apk", server.takeRequest().url.encodedPath)
    }

    @Test fun cancellationIsDurableAndOnlyExplicitRetrySubmitsAgain() = runBlocking {
        val m = manager(); m.install(apk); await(m) { it is UpdateUi.Installing }
        withTimeout(5_000) { while (store.loadEntry()?.sessionId != 1) delay(10) }
        assertTrue(store.completeSession(1, success = false, cancelled = true))
        events.emit(InstallResult.Cancelled(1)); await(m) { it is UpdateUi.Error && it.kind == UpdateFailure.CANCELLED }
        repeat(5) { m.resumePending(); delay(30) }
        assertEquals(1, installer.installs)
        scopes.first().cancel()
        val recreated = manager(); recreated.resumePending()
        await(recreated) { it is UpdateUi.Error && it.kind == UpdateFailure.CANCELLED }
        repeat(5) { recreated.resumePending(); delay(30) }
        assertEquals(1, installer.installs)
        recreated.retry(); await(recreated) { it is UpdateUi.Installing }
        withTimeout(5_000) { while (installer.installs != 2) delay(10) }
        assertEquals(listOf(1), installer.abandoned)
        assertFalse(store.completeSession(1, success = false, cancelled = true))
        assertEquals(2, store.loadEntry()?.sessionId)
    }

    @Test fun installerFailureAndSubmittedProcessRecoveryRequireExplicitAction() = runBlocking {
        val m = manager(); m.install(apk); await(m) { it is UpdateUi.Installing }
        withTimeout(5_000) { while (store.loadEntry()?.sessionId != 1) delay(10) }
        scopes.first().cancel()
        val recreated = manager(); recreated.resumePending()
        await(recreated) { it is UpdateUi.Error && it.kind == UpdateFailure.INTERRUPTED }
        recreated.resumePending(); delay(100); assertEquals(1, installer.installs)
        recreated.retry(); await(recreated) { it is UpdateUi.Installing }
        withTimeout(5_000) { while (installer.installs != 2) delay(10) }
        assertTrue(store.completeSession(2, success = false))
        events.emit(InstallResult.Failure(2, 4))
        await(recreated) { it is UpdateUi.Error && it.kind == UpdateFailure.INSTALL }
        recreated.resumePending(); delay(100); assertEquals(2, installer.installs)
        scopes.last().cancel()
        val failed = manager(); failed.resumePending()
        await(failed) { it is UpdateUi.Error && it.kind == UpdateFailure.INSTALL }
        assertEquals(2, installer.installs)
    }

    @Test fun permissionDenialAndProcessRecoveryResumeOnlyAfterGrant() = runBlocking {
        installer.allowed = false
        val m = manager(); m.install(apk); await(m) { it is UpdateUi.NeedsPermission }
        repeat(3) { m.resumePending(); delay(30) }; assertEquals(0, installer.installs)
        scopes.first().cancel()
        val recreated = manager(); recreated.resumePending(); await(recreated) { it is UpdateUi.NeedsPermission }
        installer.allowed = true; recreated.resumePending()
        await(recreated) { it is UpdateUi.Installing }
        withTimeout(5_000) { while (installer.installs != 1) delay(10) }
        repeat(5) { recreated.resumePending() }; delay(100); assertEquals(1, installer.installs)
    }

    @Test fun explicitRetryDoesNotBlessTamperedApk() = runBlocking {
        store.save(apk); store.transition(PendingUpdateStore.Phase.CANCELLED, 1)
        val m = manager(); m.resumePending(); await(m) { it is UpdateUi.Error }
        apk.appendText("tampered")
        m.retry(); await(m) { it is UpdateUi.Error && it.kind == UpdateFailure.INTEGRITY }
        assertNull(store.load()); assertEquals(0, installer.installs)
    }

    @Test fun partiallyDownloadedCancellationDeletesOnlyUpdateAndNeverInstalls() = runBlocking {
        val document = File(tmp.root, "document.txt").apply { writeText("owner fixture survives") }
        val bytes = ByteArray(500_000) { (it % 251).toByte() }; val m = manager()
        server.enqueue(MockResponse(body = Hashing.sha256Hex(bytes)))
        server.enqueue(MockResponse.Builder().body(Buffer().write(bytes)).throttleBody(65_536, 200, TimeUnit.MILLISECONDS).build())
        m.downloadAndInstall(release)
        await(m) { it is UpdateUi.Downloading && it.done in 1 until bytes.size.toLong() }
        m.cancel(); assertEquals(UpdateUi.Idle, m.state.value)
        withTimeout(5_000) { while (File(tmp.root, "updates/${release.apkName}").exists()) delay(10) }
        m.resumePending(); delay(100)
        assertEquals(0, installer.installs); assertNull(store.load()); assertEquals("owner fixture survives", document.readText())
    }
}
