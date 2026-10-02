package pro.perfectproduct.cramin.ingest

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pro.perfectproduct.cramin.data.db.DocumentEntity
import pro.perfectproduct.cramin.data.repo.DocumentFiles
import pro.perfectproduct.cramin.pipeline.ErrorCode
import pro.perfectproduct.cramin.pipeline.PipelineException
import pro.perfectproduct.cramin.util.Log
import java.io.File

/**
 * PDF (SPEC §7.3): PdfBox-Android постранично; колонтитулы — строки, одинаковые на трёх и более
 * страницах — удаляются; без текстового слоя (скан) — ошибка PDF_NO_TEXT.
 */
class PdfExtractor(context: Context) : SourceExtractor {
    init {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    override suspend fun extract(document: DocumentEntity, files: DocumentFiles): Extracted = withContext(Dispatchers.IO) {
        val file = files.inputPdf(document.id)
        if (!file.isFile) throw PipelineException(ErrorCode.STORAGE, "input.pdf missing")
        val (pages, title) = readPages(file)
        val text = joinPages(pages)
        if (text.count { it.isLetter() } < MIN_LETTERS) throw PipelineException(ErrorCode.PDF_NO_TEXT, "no text layer")
        Log.i(TAG, "doc=${document.id} pdf: pages=${pages.size} chars=${text.length}")
        Extracted.Text(text = text, title = title, langHint = null)
    }

    private fun readPages(file: File): Pair<List<List<String>>, String?> = try {
        PDDocument.load(file).use { doc ->
            if (doc.isEncrypted) throw PipelineException(ErrorCode.PDF_ENCRYPTED, "encrypted")
            val stripper = PDFTextStripper().apply { sortByPosition = true }
            val pages = (1..doc.numberOfPages).map { i ->
                stripper.startPage = i
                stripper.endPage = i
                stripper.getText(doc).lines().map { it.trimEnd() }
            }
            pages to doc.documentInformation?.title?.trim()?.takeIf { it.isNotEmpty() }
        }
    } catch (e: PipelineException) {
        throw e
    } catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
        throw PipelineException(ErrorCode.PDF_ENCRYPTED, "password required", e)
    } catch (e: Exception) {
        throw PipelineException(ErrorCode.PDF_INVALID, "pdf parse failed: ${e.javaClass.simpleName}", e)
    }

    companion object {
        private const val TAG = "Pdf"
        const val MIN_LETTERS = 50
        const val HEADER_PAGES = 3
        private val DIGITS = Regex("\\d+")

        /** Строки одинаковые (цифры не в счёт) на ≥ [HEADER_PAGES] страницах считаются колонтитулами. */
        fun headerFooterKeys(pages: List<List<String>>): Set<String> {
            if (pages.size < HEADER_PAGES) return emptySet()
            val counts = HashMap<String, Int>()
            for (page in pages) {
                val keys = page.map { normalize(it) }.filter { it.isNotEmpty() }.toSet()
                for (k in keys) counts[k] = (counts[k] ?: 0) + 1
            }
            return counts.filterValues { it >= HEADER_PAGES }.keys
        }

        fun joinPages(pages: List<List<String>>): String {
            val drop = headerFooterKeys(pages)
            val sb = StringBuilder()
            for (page in pages) {
                for (line in page) {
                    val key = normalize(line)
                    if (key.isNotEmpty() && key in drop) continue
                    sb.append(line.trim()).append('\n')
                }
            }
            return sb.toString().trim()
        }

        private fun normalize(line: String): String = DIGITS.replace(line.trim(), "#").lowercase()
    }
}
