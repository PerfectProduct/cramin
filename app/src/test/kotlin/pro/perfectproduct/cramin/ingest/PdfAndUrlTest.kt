package pro.perfectproduct.cramin.ingest

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import pro.perfectproduct.cramin.data.db.DocStatus
import pro.perfectproduct.cramin.data.db.DocumentEntity
import pro.perfectproduct.cramin.data.db.SourceType
import pro.perfectproduct.cramin.data.repo.DocumentFiles
import pro.perfectproduct.cramin.pipeline.ErrorCode
import pro.perfectproduct.cramin.pipeline.Icu4jSentenceBreaker
import pro.perfectproduct.cramin.pipeline.PipelineException
import pro.perfectproduct.cramin.pipeline.Segmenter
import pro.perfectproduct.cramin.util.Lang

@RunWith(RobolectricTestRunner::class)
class PdfAndUrlTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun urlClassifier() {
        assertTrue(UrlClassifier.isYoutube("https://www.youtube.com/watch?v=abc"))
        assertTrue(UrlClassifier.isYoutube("https://youtu.be/abc"))
        assertTrue(UrlClassifier.isYoutube("https://m.youtube.com/shorts/abc"))
        assertTrue(UrlClassifier.isYoutube("https://music.youtube.com/watch?v=abc"))
        assertFalse(UrlClassifier.isYoutube("https://example.com/youtube.com"))
        assertTrue(UrlClassifier.isUrl("https://example.com/a?b=c"))
        assertFalse(UrlClassifier.isUrl("see https://example.com now"))
        assertEquals("https://example.com/x", UrlClassifier.normalize("example.com/x"))
        assertEquals("https://example.com/x", UrlClassifier.extractUrl("  https://example.com/x \n"))
    }

    @Test
    fun headerFooterRemoval() {
        val pages = listOf(
            listOf("Report", "Body one.", "Page 1"),
            listOf("Report", "Body two.", "Page 2"),
            listOf("Report", "Body three.", "Page 3"),
            listOf("Other", "Body four.", "Page 4"),
        )
        assertEquals("Body one.\nBody two.\nBody three.\nOther\nBody four.", PdfExtractor.joinPages(pages))
        // Меньше трёх страниц — колонтитулы не угадываем.
        assertEquals("Report\nA\nReport\nB", PdfExtractor.joinPages(listOf(listOf("Report", "A"), listOf("Report", "B"))))
    }

    @Test
    fun pdfFixtureExtractsTextDropsHeadersAndJoinsHyphenation() = runTest {
        val files = DocumentFiles(tmp.root)
        val target = files.inputPdf(7)
        javaClass.classLoader!!.getResourceAsStream("fixtures/sample.pdf")!!.use { input -> target.outputStream().use { input.copyTo(it) } }
        val extractor = PdfExtractor(ApplicationProvider.getApplicationContext())
        val result = extractor.extract(doc(7), files) as Extracted.Text
        assertEquals("Riverside Quarterly", result.title)
        assertFalse("колонтитул остался", result.text.contains("Riverside Quarterly Report"))
        assertFalse("номер страницы остался", result.text.contains("Page 2"))
        assertTrue(result.text.contains("Chapter Two"))
        val sentences = Segmenter(Icu4jSentenceBreaker()).segment(result.text, Lang.EN, pdfMode = true)
        val joined = sentences.joinToString(" ") { it.text }
        assertTrue(joined, joined.contains("every morning the librarian unlocked the reading room before the first volunteer arrived."))
    }

    @Test
    fun pdfWithoutTextLayerFails() = runTest {
        val files = DocumentFiles(tmp.root)
        files.inputPdf(8).writeText("%PDF-1.4\n1 0 obj << /Type /Catalog >> endobj\ntrailer << /Root 1 0 R >>\n%%EOF")
        try {
            PdfExtractor(ApplicationProvider.getApplicationContext()).extract(doc(8), files)
            fail()
        } catch (e: PipelineException) {
            assertEquals(ErrorCode.PDF_INVALID, e.code)
        }
    }

    private fun doc(id: Long) = DocumentEntity(
        id = id, title = "sample", emoji = "📄", sourceType = SourceType.PDF, sourceRef = "sample.pdf", sourceLang = "", targetLang = "ru",
        status = DocStatus.QUEUED, progress = 0f, errorCode = null, errorMessage = null, direction = null, pipelineVersion = 0,
        briefJson = null, modelsSnapshotJson = null, promptTokens = 0, completionTokens = 0, costUsd = null, audioSeconds = 0,
        wordCount = 0, createdAt = 0, updatedAt = 0,
    )
}
