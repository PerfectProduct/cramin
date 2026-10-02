package pro.perfectproduct.cramin.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import pro.perfectproduct.cramin.util.Hashing
import java.io.File
import java.io.FileOutputStream

/** Only a verified APK's basename and digest; no network address or credentials. */
class PendingUpdateStore(private val record: File, private val apkDir: File) {
    @Serializable private data class Pending(val name: String, val sha256: String)

    @Synchronized fun save(apk: File) {
        require(apk.isFile && apk.canonicalFile.parentFile == apkDir.canonicalFile)
        val data = Json.encodeToString(Pending(apk.name, Hashing.sha256Hex(apk)))
        record.parentFile?.mkdirs()
        val tmp = File(record.path + ".tmp")
        try {
            FileOutputStream(tmp).use { it.write(data.toByteArray()); it.fd.sync() }
            check(tmp.renameTo(record)) { "pending update persistence failed" }
        } finally { tmp.delete() }
    }

    @Synchronized fun load(): File? {
        if (!record.isFile) return null
        val apk = runCatching {
            val p = Json.decodeFromString<Pending>(record.readText())
            require(p.name == File(p.name).name && p.name.endsWith(".apk"))
            File(apkDir, p.name).takeIf { it.isFile && it.canonicalFile.parentFile == apkDir.canonicalFile && Hashing.sha256Hex(it) == p.sha256 }
        }.getOrNull()
        if (apk == null) clear()
        return apk
    }

    @Synchronized fun clear() { record.delete(); File(record.path + ".tmp").delete() }
}
