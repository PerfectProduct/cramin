package pro.perfectproduct.cramin.chatgpt

import android.util.AtomicFile
import kotlinx.serialization.json.*
import java.io.File
import java.io.FileOutputStream

/** Independent of OAuth: login, disconnect and process restart cannot reset the one-shot allowance. */
internal class ProbeAttemptStore(private val directory: File) {
    private val marker = File(directory, "chatgpt-schema-probe-v1.used")
    private val report = AtomicFile(File(directory, "chatgpt-schema-probe-v1.json"))

    fun read(): JsonObject? = synchronized(lock) {
        if (!marker.exists()) return@synchronized null
        // A torn/missing report is still a used attempt. Never recover by deleting the marker.
        runCatching { Json.parseToJsonElement(report.readFully().toString(Charsets.UTF_8)).jsonObject }
            .getOrElse { unknown() }
    }

    fun claim(model: String): Boolean = synchronized(lock) {
        // createNewFile is atomic, including between controller/store instances.
        if (!marker.createNewFile()) return@synchronized false
        FileOutputStream(marker).use { it.write(1); it.fd.sync() }
        save(unknown(model))
        check(read() != null)
        true
    }

    fun save(value: JsonObject) = synchronized(lock) {
        check(marker.exists())
        val out = report.startWrite()
        try {
            out.write(value.toString().toByteArray())
            out.fd.sync()
            report.finishWrite(out)
        } catch (t: Throwable) { report.failWrite(out); throw t }
    }

    private fun unknown(model: String? = null) = buildJsonObject {
        put("status", "unknown"); model?.let { put("model", it) }
        put("message", "Попытка использована. Завершение не подтверждено; повтор запрещён.")
        put("usage", JsonNull)
    }

    companion object { private val lock = Any() }
}
