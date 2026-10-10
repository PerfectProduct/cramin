package pro.perfectproduct.cramin.chatgpt

import android.util.AtomicFile
import kotlinx.serialization.json.*
import java.io.File
import java.time.Instant
import java.util.UUID

/** Separate from OAuth and the immutable v1 one-shot files. No recovery path sends a request. */
internal class ProbeHistoryStore(
    directory: File,
    private val processId: String = PROCESS_ID,
    private val now: () -> String = { Instant.now().toString() },
) {
    private val file = AtomicFile(File(directory, "chatgpt-schema-probe-history-v1.json"))
    private val legacy = ProbeAttemptStore(directory)

    fun history(): List<JsonObject> = synchronized(lock) {
        val records = recover()
        val old = legacy.read()?.let { report -> JsonObject(report + mapOf(
            "attempt_id" to JsonPrimitive("legacy-v1"), "source" to JsonPrimitive("legacy_one_shot"),
            "started_at" to JsonNull, "finished_at" to JsonNull, "lifecycle" to JsonPrimitive("finished"),
            "terminal_status" to terminalStatus(report),
        )) }
        listOfNotNull(old) + records.map { JsonObject(it - "owner_process") }
    }

    /** Durable claim precedes POST. A second controller in this process cannot overlap it. */
    fun start(model: String): String? = synchronized(lock) {
        val records = recover()
        if (records.any { it.string("lifecycle") == "in_progress" }) return@synchronized null
        val id = UUID.randomUUID().toString()
        val record = buildJsonObject {
            put("attempt_id", id); put("source", "manual_debug"); put("owner_process", processId)
            put("started_at", now()); put("finished_at", JsonNull); put("model", model)
            put("lifecycle", "in_progress"); put("status", "in_progress"); put("schema_valid", false)
            put("terminal_status", JsonNull); put("terminal_observation", "unknown")
            put("usage", JsonNull); put("cost_usd", JsonNull)
        }
        write(records + record)
        id
    }

    fun finish(id: String, report: JsonObject) = synchronized(lock) {
        val records = read()
        val index = records.indexOfFirst { it.string("attempt_id") == id }
        check(index >= 0)
        val pending = records[index]
        check(pending.string("lifecycle") == "in_progress" && pending.string("owner_process") == processId)
        records[index] = JsonObject((pending - "owner_process") + report + mapOf(
            "lifecycle" to JsonPrimitive("finished"), "finished_at" to JsonPrimitive(now()),
            "terminal_status" to terminalStatus(report),
            "terminal_observation" to (report["terminal_observation"]
                ?: (report["diagnostic"] as? JsonObject)?.get("terminal_observation") ?: JsonPrimitive("unknown")),
        ))
        write(records)
    }

    private fun recover(): MutableList<JsonObject> {
        val records = read()
        var changed = false
        for (index in records.indices) {
            val record = records[index]
            if (record.string("lifecycle") == "in_progress" && record.string("owner_process") != processId) {
                records[index] = JsonObject((record - "owner_process") + buildJsonObject {
                    put("lifecycle", "finished"); put("status", "unknown"); put("schema_valid", false)
                    put("finished_at", JsonNull); put("recovered_at", now())
                    put("terminal_status", JsonNull); put("terminal_observation", "unknown"); put("usage", JsonNull)
                    put("message", "Процесс потерян: результат неизвестен. Запрос мог расходовать план или кредиты. Автоматической повторной отправки нет.")
                })
                changed = true
            }
        }
        if (changed) write(records)
        return records
    }

    private fun read(): MutableList<JsonObject> {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return mutableListOf()
        val root = Json.parseToJsonElement(file.readFully().toString(Charsets.UTF_8)).jsonObject
        check(root["version"] == JsonPrimitive(1))
        return root.getValue("attempts").jsonArray.map { it.jsonObject }.toMutableList()
    }

    private fun write(records: List<JsonObject>) {
        val value = buildJsonObject { put("version", 1); put("attempts", JsonArray(records)) }
        val out = file.startWrite()
        try {
            out.write(value.toString().toByteArray(Charsets.UTF_8)); out.fd.sync(); file.finishWrite(out)
        } catch (t: Throwable) { file.failWrite(out); throw t }
    }

    private fun terminalStatus(report: JsonObject): JsonElement =
        if (report.string("event") == "response.completed" && report["schema_valid"] == JsonPrimitive(true))
            JsonPrimitive("completed")
        else (report["diagnostic"] as? JsonObject)?.get("terminal_status") ?: JsonNull

    companion object {
        private val lock = Any()
        private val PROCESS_ID = UUID.randomUUID().toString()
    }
}

/** Contains only synthetic validated results and the existing allowlisted diagnostic fields. */
internal fun probeHistoryReport(history: List<JsonObject>): String = Json { prettyPrint = true }.encodeToString(
    JsonObject.serializer(), buildJsonObject {
        put("format", "cramin-chatgpt-debug-history-v1")
        put("success_requires", "response.completed + completed status + matching model + valid TRANSLATE schema")
        put("attempts", JsonArray(history))
    },
)
