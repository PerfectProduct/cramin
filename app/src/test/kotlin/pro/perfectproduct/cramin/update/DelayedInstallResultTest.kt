package pro.perfectproduct.cramin.update

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pro.perfectproduct.cramin.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Receiver has accepted A, but its Flow event reaches an IO manager only after retry B. */
class DelayedInstallResultTest {
    @get:Rule val tmp = TemporaryFolder()
    private class Delivery(val result: InstallResult) {
        val accepted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val applied = CompletableDeferred<Unit>()
    }

    @Test fun acceptedCancellationCannotOverwriteRetrySession() = delayed(InstallResult.Cancelled(1))
    @Test fun acceptedFailureCannotOverwriteRetrySession() = delayed(InstallResult.Failure(1, 4))
    @Test fun acceptedSuccessCannotClearNextSession() = delayed(InstallResult.Success(1))
    @Test fun staleEventCannotCleanUpAnotherSessionsCorruptApk() = delayed(InstallResult.Cancelled(1), tamperB = true)

    private fun delayed(old: InstallResult, tamperB: Boolean = false) = runBlocking {
        Log.install { _, _, _, _ -> }
        val dir = tmp.newFolder("updates")
        val store = PendingUpdateStore(File(tmp.root, "pending.json"), dir)
        val receiverStore = PendingUpdateStore(File(tmp.root, "pending.json"), dir)
        val apk = File(dir, "fixture.apk").apply { writeText("verified fixture") }
        val submissions = AtomicInteger()
        val submitted = Channel<Int>(Channel.UNLIMITED)
        val installer = object : UpdateInstaller {
            override fun canInstall() = true
            override fun validate(apk: File) = apk.isFile
            override suspend fun install(apk: File) {
                val id = submissions.incrementAndGet()
                store.transition(PendingUpdateStore.Phase.SUBMITTED, id)
                submitted.send(id)
            }
        }
        // Session A survives its original process. The restored manager subscribes on IO.
        store.save(apk); installer.install(apk); assertEquals(1, submitted.receive())
        val deliveries = Channel<Delivery>(Channel.UNLIMITED)
        val results = flow {
            for (d in deliveries) {
                d.accepted.complete(Unit)
                d.release.await() // deterministic barrier, no sleeps or scheduler luck
                emit(d.result)
                d.applied.complete(Unit)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            withTimeout(5_000) {
                val m = UpdateManager(UpdateChecker(OkHttpClient(), 1), ApkDownloader(OkHttpClient(), tmp.root), installer, scope, store, results)
                assertTrue(receiverStore.completeSession(1, success = old is InstallResult.Success, cancelled = old is InstallResult.Cancelled))
                val a = Delivery(old); deliveries.send(a); a.accepted.await()
                if (old is InstallResult.Success) {
                    // Success permits another explicitly requested update, not automatic retry.
                    m.install(apk)
                } else {
                    m.resumePending()
                    m.state.first { it is UpdateUi.Error }
                    m.retry()
                }
                assertEquals(2, submitted.receive())
                assertEquals(UpdateUi.Installing(apk), m.state.value)
                val recordBefore = File(tmp.root, "pending.json").readText()
                if (tamperB) apk.appendText("tampered B")
                a.release.complete(Unit); a.applied.await()
                assertEquals(recordBefore, File(tmp.root, "pending.json").readText())
                if (tamperB) apk.writeText("verified fixture")
                assertEquals("Delayed A must leave B submitted", PendingUpdateStore.Phase.SUBMITTED, store.loadEntry()?.phase)
                assertEquals(2, store.loadEntry()?.sessionId)
                assertEquals(UpdateUi.Installing(apk), m.state.value)
                assertEquals(2, submissions.get())
                assertTrue(receiverStore.completeSession(2, success = true))
                val b = Delivery(InstallResult.Success(2)); b.release.complete(Unit); deliveries.send(b); b.applied.await()
                assertEquals(UpdateUi.Idle, m.state.value); assertNull(store.load())
                assertEquals(2, submissions.get())
            }
        } finally { scope.cancel(); submitted.close(); deliveries.close(); Log.install(null) }
    }
}
