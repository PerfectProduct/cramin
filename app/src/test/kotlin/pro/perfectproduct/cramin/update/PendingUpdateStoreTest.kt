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
    @Test fun rejectsPathTraversal() {
        val dir = tmp.newFolder("updates")
        val record = tmp.root.resolve("pending.json")
        record.writeText("""{"name":"../outside.apk","sha256":"0"}""")
        assertNull(PendingUpdateStore(record, dir).load())
        assertFalse(record.exists())
    }
}
