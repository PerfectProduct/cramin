package pro.perfectproduct.cramin.data.repo

import java.io.File
import pro.perfectproduct.cramin.ingest.TextProvenance
import pro.perfectproduct.cramin.llm.LlmJson
import pro.perfectproduct.cramin.util.Hashing

/**
 * Файлы документа в `files/docs/{id}/` (SPEC §6.11): входные данные и извлечённый текст.
 * Тексты документов никогда не логируются; здесь только пути.
 */
class DocumentFiles(private val filesRoot: File) {
    fun dir(documentId: Long): File = File(filesRoot, "docs/$documentId").apply { mkdirs() }

    /** Извлечённый текст источника (после транскрипции — её результат). */
    fun sourceText(documentId: Long): File = File(dir(documentId), "source.txt")

    /** A killed writer must never leave a partial file that resume mistakes for a completed source. */
    fun writeSourceText(documentId: Long, text: String, provenance: TextProvenance = TextProvenance()) {
        val record = provenance.copy(sourceHash = Hashing.sha256Hex(text.toByteArray(Charsets.UTF_8)))
        // Write metadata first; a crash or replacement cannot attach it to different source bytes.
        atomicWrite(File(dir(documentId), "text-provenance.json"), LlmJson.strict.encodeToString(TextProvenance.serializer(), record))
        atomicWrite(sourceText(documentId), text)
    }

    fun textProvenance(documentId: Long): TextProvenance = runCatching {
        val record = LlmJson.strict.decodeFromString<TextProvenance>(File(dir(documentId), "text-provenance.json").readText())
        record.takeIf { it.sourceHash == Hashing.sha256Hex(sourceText(documentId)) } ?: TextProvenance()
    }.getOrDefault(TextProvenance())

    private fun atomicWrite(target: File, text: String) {
        val temporary = File(target.path + ".part")
        try {
            java.io.FileOutputStream(temporary).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            java.nio.file.Files.move(temporary.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    /** Per-attempt terminal evidence has no source/output text, credentials or raw headers. */
    fun recordResponseEvidence(documentId: Long, jobId: Long, evidence: kotlinx.serialization.json.JsonObject) {
        val directory = File(dir(documentId), "response-evidence").apply { mkdirs() }
        atomicWrite(File(directory, "$jobId-${java.util.UUID.randomUUID()}.json"), evidence.toString())
    }

    /** Вставленный пользователем текст (SourceType.TEXT). */
    fun inputText(documentId: Long): File = File(dir(documentId), "input.txt")

    /** Копия PDF, сделанная при импорте, чтобы не зависеть от временных прав на content://. */
    fun inputPdf(documentId: Long): File = File(dir(documentId), "input.pdf")

    /** Временные файлы транскрипции (аудио и его куски); удаляются после успеха. */
    fun audioDir(documentId: Long): File = File(dir(documentId), "audio").apply { mkdirs() }

    fun deleteAll(documentId: Long) {
        File(filesRoot, "docs/$documentId").deleteRecursively()
    }
}
