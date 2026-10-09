package pro.perfectproduct.cramin.diagnostics

import android.database.Cursor
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import pro.perfectproduct.cramin.data.db.CraminDatabase
import pro.perfectproduct.cramin.util.Hashing

data class ExportAppInfo(val applicationId: String, val versionName: String, val versionCode: Int)

/** Read-only, document-scoped archive. No repositories, settings, secrets, workers or network. */
class DocumentDiagnosticExporter(
    private val db: CraminDatabase,
    private val filesRoot: File,
    private val app: ExportAppInfo,
) {
    suspend fun save(documentId: Long, open: () -> OutputStream?, discardIncomplete: () -> Unit) = withContext(Dispatchers.IO) {
        try {
            val archive = capture(documentId)
            currentCoroutineContext().ensureActive()
            archive.writeTo(open() ?: throw IOException("Destination unavailable"))
        } catch (t: Throwable) {
            runCatching { discardIncomplete() }
            throw t
        }
    }

    suspend fun capture(documentId: Long): DiagnosticArchive = withContext(Dispatchers.IO) {
        db.documentLock(documentId).withLock {
            db.withTransaction {
                val document = rows("SELECT * FROM Document WHERE id = ?", documentId).singleOrNull()
                    ?: throw IOException("Document unavailable")
                if (document.getValue("status").jsonPrimitive.content != "READY") throw IOException("Document not READY")
                val sourceRef = document.getValue("sourceRef").jsonPrimitive.content
                val redact = SourceReferenceRedactor(sourceRef)
                val entries = linkedMapOf<String, ByteArray>()
                val redacted = mutableSetOf<String>()
                val missing = mutableListOf<String>()
                val counts = linkedMapOf<String, Int>()
                fun add(name: String, bytes: ByteArray) {
                    val clean = redact.text(bytes.decodeToString(throwOnInvalidSequence = true)).toByteArray(Charsets.UTF_8)
                    entries[name] = clean
                    if (!bytes.contentEquals(clean)) redacted += name
                }
                fun json(name: String, value: JsonElement) = add(name, value.toString().toByteArray(Charsets.UTF_8))
                val cleanDocument = JsonObject(document + ("sourceRef" to JsonPrimitive(redact.reference)))
                json("data/Document.json", cleanDocument)
                if (sourceRef != redact.reference) redacted += "data/Document.json"
                for (table in listOf("Sentence", "Segment", "Job", "Card", "ReprocessState")) {
                    val data = rows("SELECT * FROM $table WHERE documentId = ? ORDER BY " +
                        if (table == "ReprocessState") "documentId" else "id", documentId)
                    counts[table] = data.size
                    json("data/$table.json", JsonArray(data))
                    if (data.isEmpty()) missing += "data/$table.json: no saved rows"
                    if (table == "Job") data.filter { it["responseJson"] == JsonNull }.forEach {
                        missing += "Job ${it.getValue("id")}: responseJson absent"
                    }
                }
                val senses = rows("SELECT s.* FROM Sense s JOIN Card c ON c.id = s.cardId WHERE c.documentId = ? ORDER BY s.id", documentId)
                val occurrences = rows("SELECT o.* FROM Occurrence o JOIN Card c ON c.id = o.cardId " +
                    "JOIN Sentence s ON s.id = o.sentenceId WHERE c.documentId = ? AND s.documentId = ? ORDER BY o.id", documentId, documentId)
                for ((name, data) in listOf("Sense" to senses, "Occurrence" to occurrences)) {
                    counts[name] = data.size
                    json("data/$name.json", JsonArray(data))
                    if (data.isEmpty()) missing += "data/$name.json: no saved rows"
                }
                for ((field, name) in listOf("modelsSnapshotJson" to "processing", "topicSnapshotJson" to "topic", "briefJson" to "brief")) {
                    val raw = document[field]?.jsonPrimitive?.contentOrNull
                    if (raw == null) missing += "snapshots/$name.json: $field absent"
                    else add("snapshots/$name.json", raw.toByteArray(Charsets.UTF_8))
                }
                // Fixed names, no directory walk; never create missing document directories.
                val dir = File(filesRoot.canonicalFile, "docs/$documentId")
                if (dir.canonicalFile != dir) throw IOException("Document directory is not local")
                for (name in listOf("source.txt", "input.txt", "text-provenance.json", "status-snapshot.json")) {
                    val file = File(dir, name)
                    if (file.canonicalFile != file) throw IOException("Document file is not local")
                    if (!file.exists()) missing += "files/$name: absent"
                    else {
                        if (!file.isFile) throw IOException("Document file unavailable")
                        add("files/$name", file.readBytes())
                    }
                }
                val manifest = buildJsonObject {
                    put("format", "cramin-document-diagnostics"); put("formatVersion", 1)
                    put("exportedAt", Instant.now().toString()); put("documentId", documentId)
                    put("databaseSchemaVersion", CraminDatabase.VERSION)
                    putJsonObject("exporter") {
                        put("applicationId", app.applicationId); put("versionName", app.versionName); put("versionCode", app.versionCode)
                    }
                    putJsonObject("rowCounts") { counts.forEach { (table, count) -> put(table, count) } }
                    put("missingData", JsonArray(missing.map(::JsonPrimitive)))
                    put("redactionPolicy", "Original source URL credentials, query and fragment removed, including matching links in text/JSON; no SecretStore/DataStore or full database")
                    put("responseMeaning", "Saved responseJson, not a complete original HTTP/attempt log; EXTRACT may combine length subparts")
                    put("manifestHashExcluded", true)
                    putJsonObject("files") {
                        entries.forEach { (name, bytes) -> putJsonObject(name) {
                            put("sha256", Hashing.sha256Hex(bytes)); put("size", bytes.size); put("redacted", name in redacted)
                        } }
                    }
                }
                entries["manifest.json"] = manifest.toString().toByteArray(Charsets.UTF_8)
                DiagnosticArchive(entries)
            }
        }
    }

    private fun rows(sql: String, vararg args: Long): List<JsonObject> =
        db.openHelper.readableDatabase.query(SimpleSQLiteQuery(sql, args.toTypedArray())).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(buildJsonObject {
                    cursor.columnNames.forEachIndexed { i, name -> put(name, when (cursor.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> JsonNull
                        Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(cursor.getLong(i))
                        Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(cursor.getDouble(i))
                        Cursor.FIELD_TYPE_STRING -> JsonPrimitive(cursor.getString(i))
                        else -> throw IOException("Unsupported document field")
                    }) }
                })
            }
        }
}

class DiagnosticArchive internal constructor(private val entries: Map<String, ByteArray>) {
    /** Takes ownership of the output. Caller removes an incomplete SAF document on failure. */
    suspend fun writeTo(output: OutputStream) {
        // ZipOutputStream.close() can fail while finishing a partial entry, before closing its sink.
        output.use { sink -> ZipOutputStream(sink).use { zip ->
            for ((name, bytes) in entries) {
                currentCoroutineContext().ensureActive()
                zip.putNextEntry(ZipEntry(name))
                for (offset in bytes.indices step 64 * 1024) {
                    currentCoroutineContext().ensureActive()
                    zip.write(bytes, offset, minOf(64 * 1024, bytes.size - offset))
                }
                zip.closeEntry()
            }
        } }
    }
}

/** Preserve saved JSON byte-for-byte unless its string values contain the original source URL. */
internal class SourceReferenceRedactor(source: String) {
    private val original = source.toHttpUrlOrNull()
    val reference: String = original?.newBuilder()?.username("")?.password("")?.query(null)?.fragment(null)?.build()?.toString() ?: ""
    private val links = Regex("https?://[^\\s<>\"\\\\]+", RegexOption.IGNORE_CASE)
    fun text(raw: String): String {
        if (original == null) return raw
        val parsed = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
        if (parsed != null) {
            val clean = json(parsed)
            return if (clean == parsed) raw else clean.toString()
        }
        return string(raw)
    }
    private fun json(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.entries.associate { string(it.key) to json(it.value) })
        is JsonArray -> JsonArray(value.map(::json))
        is JsonPrimitive -> if (value.isString) JsonPrimitive(text(value.content)) else value
    }
    private fun string(raw: String): String {
        val full = links.replace(raw) { match ->
            val url = match.value.toHttpUrlOrNull()
            if (url != null && url.host == original?.host && url.encodedPath == original.encodedPath) reference else match.value
        }
        // The initial URL document title can be a truncated URL without its scheme.
        val title = original?.toString()?.substringAfter("://").orEmpty()
        return if (full.isNotEmpty() && title.startsWith(full) && (full.contains('?') || full.contains('@') || full.contains('#'))) reference else full
    }
}
