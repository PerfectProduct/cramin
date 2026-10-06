package pro.perfectproduct.cramin.update

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PendingUpdateStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun survivesRecreationAndRejectsAlteredOrMissingApk() {
        val dir = tmp.newFolder("updates")
        val record = tmp.root.resolve("pending.json")
        val apk = dir.resolve("sample.apk").apply { writeText("synthetic") }
        PendingUpdateStore(record, dir).save(apk)
        assertEquals(apk, PendingUpdateStore(record, dir).load())
        apk.appendText("changed")
        assertNull(PendingUpdateStore(record, dir).load())
        assertFalse(record.exists())
        PendingUpdateStore(record, dir).save(apk)
        apk.delete()
        assertNull(PendingUpdateStore(record, dir).load())
    }
    @Test fun legacyRecordRequiresExplicitActionAndLateCallbackCannotClearNewAttempt() {
        val dir = tmp.newFolder("updates")
        val record = tmp.root.resolve("pending.json")
        val apk = dir.resolve("sample.apk").apply { writeText("synthetic") }
        record.writeText("""{"name":"sample.apk","sha256":"${pro.perfectproduct.cramin.util.Hashing.sha256Hex(apk)}"}""")
        val store = PendingUpdateStore(record, dir)
        assertEquals(PendingUpdateStore.Phase.RETRY, store.loadEntry()?.phase)
        store.transition(PendingUpdateStore.Phase.SUBMITTED, 1)
        val receiverStore = PendingUpdateStore(record, dir)
        assertTrue(receiverStore.completeSession(1, success = false, cancelled = true))
        assertEquals(PendingUpdateStore.Phase.CANCELLED, store.loadEntry()?.phase)
        store.transition(PendingUpdateStore.Phase.SUBMITTED, 2)
        assertFalse(receiverStore.completeSession(1, success = true))
        assertEquals(2, store.loadEntry()?.sessionId)
        assertTrue(receiverStore.completeSession(2, success = true))
        assertNull(store.load())
    }
    @Test fun rejectsPathTraversal() {
        val dir = tmp.newFolder("updates")
        val record = tmp.root.resolve("pending.json")
        record.writeText("""{"name":"../outside.apk","sha256":"0"}""")
        assertNull(PendingUpdateStore(record, dir).load())
        assertFalse(record.exists())
    }
    @Test fun successfulIdentitySurvivesRecreationAndIsConsumedOnlyForItsSession() {
        val dir = tmp.newFolder("updates")
        val record = tmp.root.resolve("pending.json")
        val apk = dir.resolve("sample.apk").apply { writeText("synthetic") }
        val store = PendingUpdateStore(record, dir)
        store.save(apk); store.transition(PendingUpdateStore.Phase.SUBMITTED, 1)
        assertTrue(store.completeSession(1, success = true))
        val restored = PendingUpdateStore(record, dir)
        assertEquals(PendingUpdateStore.Phase.SUCCEEDED, restored.loadEntry()?.phase)
        assertNull(restored.load()) // success is not an installable pending APK
        assertFalse(restored.applyTerminalResult(2, PendingUpdateStore.Phase.SUCCEEDED) { fail("wrong identity") })
        var applied = false
        assertTrue(restored.applyTerminalResult(1, PendingUpdateStore.Phase.SUCCEEDED) {
            assertNull(restored.loadEntry()) // cleared before UI can request another update
            applied = true
        })
        assertTrue(applied); assertFalse(record.exists())
        store.save(apk); store.transition(PendingUpdateStore.Phase.SUBMITTED, 2)
        assertFalse(restored.applyTerminalResult(1, PendingUpdateStore.Phase.SUCCEEDED) { fail("stale success") })
        assertEquals(2, store.loadEntry()?.sessionId)
    }
}
