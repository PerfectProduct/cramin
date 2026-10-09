package pro.perfectproduct.cramin.ingest

import java.nio.charset.Charset
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pro.perfectproduct.cramin.data.db.*
import pro.perfectproduct.cramin.data.repo.DocumentFiles
import pro.perfectproduct.cramin.pipeline.*
import pro.perfectproduct.cramin.util.Lang
import pro.perfectproduct.cramin.util.Log

class ArticleTextResponseTest {
    @get:Rule val temporary = TemporaryFolder()
    private val text = (1..20).joinToString("\n\n") {
        "### Раздел $it\nПервая строка сохраняется. Это учебный текст.\n- Вторая строка содержит <T> и &amp; буквально."
    }

    private fun doc(url: String) = DocumentEntity(
        id = 1, title = "fixture", emoji = "", sourceType = SourceType.URL, sourceRef = url,
        sourceLang = "ru", targetLang = "en", status = DocStatus.QUEUED, progress = 0f,
        errorCode = null, errorMessage = null, direction = null, pipelineVersion = 0,
        briefJson = null, modelsSnapshotJson = null, promptTokens = 0, completionTokens = 0,
        costUsd = null, audioSeconds = 0, wordCount = 0, createdAt = 0, updatedAt = 0,
    )

    private suspend fun response(bytes: ByteArray, contentType: String, path: String = "/download"): Extracted.Text {
        // An application interceptor returns the whole response: no DNS/socket/server/LLM.
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", contentType).body(bytes.toResponseBody(contentType.toMediaType())).build()
        }.build()
        Log.install { _, _, _, _ -> }
        return try {
            ArticleExtractor(client).extract(doc("https://fixture.invalid$path"), DocumentFiles(temporary.root)) as Extracted.Text
        } finally { Log.install(null) }
    }

    @Test fun markdownUrlAndTextHaveIdenticalSentencesAndPlans() = runTest {
        val files = DocumentFiles(temporary.root)
        files.inputText(1).writeText(text)
        val pasted = PlainTextExtractor().extract(doc("").copy(sourceType = SourceType.TEXT), files) as Extracted.Text
        val url = response(text.toByteArray(), "Text/Markdown; charset=UTF-8")
        assertEquals(pasted.text, url.text)
        val segmenter = Segmenter(Icu4jSentenceBreaker())
        val fromText = segmenter.segment(pasted.text, Lang.RU)
        val fromUrl = segmenter.segment(url.text, Lang.RU)
        assertEquals(fromText, fromUrl)
        assertEquals(20, fromUrl.map { it.paragraphIdx }.distinct().size)
        val sectionWords = SectionPlanner.sectionWords(100, 1024, 2.6)
        assertEquals(SectionPlanner.plan(fromText, sectionWords), SectionPlanner.plan(fromUrl, sectionWords))
        // Identical controlled translation spans; real EXTRACT plans also depend on LLM translations.
        fun spans(s: List<SentenceDraft>) = s.map { SegmentSpan(it.idx, it.idx, it.words) }
        assertEquals(ChunkPlanner.plan(spans(fromText), 100), ChunkPlanner.plan(spans(fromUrl), 100))
    }

    @Test fun plainTextOnHtmlUrlPreservesCrLfAndLiteralMarkup() = runTest {
        val original = text.replace("\n", "\r\n")
        assertEquals(original, response(original.toByteArray(), "text/plain", "/article.html").text)
    }

    @Test fun declaredCharsetDecodesPlainText() = runTest {
        assertEquals(text, response(text.toByteArray(Charset.forName("windows-1251")), "text/plain; charset=windows-1251").text)
    }

    @Test fun bomOverridesCharsetAndMissingCharsetDefaultsToUtf8() = runTest {
        val utf16 = byteArrayOf(0xff.toByte(), 0xfe.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        assertEquals(text, response(utf16, "text/markdown; charset=utf-8").text)
        assertEquals(text, response(text.toByteArray(Charsets.UTF_8), "text/markdown").text)
    }

    @Test fun htmlOnMarkdownUrlStillUsesReadability() = runTest {
        val html = javaClass.classLoader!!.getResourceAsStream("fixtures/article.html")!!.use { it.readBytes() }
        val result = response(html, "text/html; charset=utf-8", "/article.md")
        assertTrue(result.text.contains("One volunteer repaired damaged books."))
        assertFalse(result.text.contains("Privacy policy"))
        assertFalse(result.text.contains("Buy now"))
        assertNotNull(result.title)
    }

    @Test fun shortTextResponseRetainsArticleMinimum() = runTest {
        try {
            response("Short.".toByteArray(), "text/plain")
            fail("Expected article extraction error")
        } catch (e: PipelineException) {
            assertEquals(ErrorCode.ARTICLE_EXTRACT, e.code)
        }
    }
}
