package pro.perfectproduct.cramin.data.repo

import java.io.File

/**
 * Файлы документа в `files/docs/{id}/` (SPEC §6.11): входные данные и извлечённый текст.
 * Тексты документов никогда не логируются; здесь только пути.
 */
class DocumentFiles(private val filesRoot: File) {
    fun dir(documentId: Long): File = File(filesRoot, "docs/$documentId").apply { mkdirs() }

    /** Извлечённый текст источника (после транскрипции — её результат). */
    fun sourceText(documentId: Long): File = File(dir(documentId), "source.txt")

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
