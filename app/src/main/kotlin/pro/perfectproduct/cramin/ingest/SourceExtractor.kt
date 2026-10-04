package pro.perfectproduct.cramin.ingest

import pro.perfectproduct.cramin.data.db.DocumentEntity
import pro.perfectproduct.cramin.data.repo.DocumentFiles
import pro.perfectproduct.cramin.util.Lang
import java.io.File

/** Что получилось извлечь из источника (SPEC §5.3): текст или аудио, которому нужна транскрипция. */
sealed interface Extracted {
    val title: String?
    val langHint: Lang?

    data class Text(val text: String, override val title: String?, override val langHint: Lang?, val provenance: TextProvenance = TextProvenance()) : Extracted

    /** Аудиофайл AAC/M4A во временном каталоге документа; удаляется после транскрипции. */
    data class Audio(val file: File, override val title: String?, override val langHint: Lang?, val durationSeconds: Int, val provenance: TextProvenance = TextProvenance()) : Extracted
}

/** Источник за интерфейсом (SPEC §5.2): статья, YouTube, PDF, вставленный текст. Ошибки — PipelineException. */
interface SourceExtractor {
    suspend fun extract(document: DocumentEntity, files: DocumentFiles): Extracted
}

/** Вставленный текст (SPEC §7.4): лежит в `input.txt` с момента импорта. */
class PlainTextExtractor : SourceExtractor {
    override suspend fun extract(document: DocumentEntity, files: DocumentFiles): Extracted {
        val f = files.inputText(document.id)
        val text = if (f.isFile) f.readText() else ""
        return Extracted.Text(text = text, title = null, langHint = Lang.fromCode(document.sourceLang))
    }
}
