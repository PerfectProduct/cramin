package pro.perfectproduct.cramin.ingest

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import pro.perfectproduct.cramin.data.db.DocStatus
import pro.perfectproduct.cramin.data.db.DocumentEntity
import pro.perfectproduct.cramin.data.db.SourceType
import pro.perfectproduct.cramin.data.repo.DocumentFiles
import pro.perfectproduct.cramin.pipeline.ErrorCode
import pro.perfectproduct.cramin.pipeline.PipelineException
import pro.perfectproduct.cramin.util.Log
import java.io.File

class ArticleExtractorTest {
    private val html: String = javaClass.classLoader!!.getResourceAsStream("fixtures/article.html")!!.readBytes().toString(Charsets.UTF_8)
    private val server = MockWebServer()

    @Before
    fun setUp() {
        Log.install { _, _, _, _ -> }
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
        Log.install(null)
    }

    @Test
    fun readabilityKeepsArticleParagraphsDropsChrome() {
        val result = ArticleExtractor.parse("https://example.org/library", html)
        assertEquals("The Riverside Library", result.title?.removeSuffix(" — Town Chronicle"))
        val paragraphs = result.text.split("\n\n")
        assertTrue(paragraphs.size >= 6)
        assertTrue(result.text.contains("Dr. Alvarez, unlocked the reading room"))
        assertTrue(result.text.contains("One volunteer repaired damaged books."))
        assertTrue(result.text.contains("Nobody in town called it a heritage site."))
        assertFalse(result.text.contains("Buy now"))
        assertFalse(result.text.contains("Privacy policy"))
        assertFalse(result.text.contains("Ten tips for readers"))
        assertTrue(paragraphs.all { !it.contains('\n') })
    }

    @Test
    fun tooShortPageIsAnError() {
        try {
            ArticleExtractor.parse("https://example.org/x", "<html><body><p>Short.</p></body></html>")
            fail()
        } catch (e: PipelineException) {
            assertEquals(ErrorCode.ARTICLE_EXTRACT, e.code)
        }
    }

    @Test
    fun fetchFollowsRedirectsWithMobileUserAgent() = runTest {
        server.enqueue(MockResponse(code = 302, headers = headersOf("Location", server.url("/final").toString())))
        server.enqueue(MockResponse(body = html, headers = headersOf("Content-Type", "text/html; charset=utf-8")))
        val extractor = ArticleExtractor(OkHttpClient())
        val doc = doc(server.url("/start").toString())
        val result = extractor.extract(doc, DocumentFiles(File(System.getProperty("java.io.tmpdir"), "cramin-test")))
        assertTrue(result is Extracted.Text && result.text.contains("reading room"))
        val first = server.takeRequest()
        assertEquals(ArticleExtractor.MOBILE_USER_AGENT, first.headers["User-Agent"])
        assertEquals("/final", server.takeRequest().url.encodedPath)
    }

    @Test
    fun httpErrorIsArticleExtractError() = runTest {
        server.enqueue(MockResponse(code = 404))
        try {
            ArticleExtractor(OkHttpClient()).extract(doc(server.url("/missing").toString()), DocumentFiles(File(System.getProperty("java.io.tmpdir"), "cramin-test")))
            fail()
        } catch (e: PipelineException) {
            assertEquals(ErrorCode.ARTICLE_EXTRACT, e.code)
        }
    }

    private fun doc(url: String) = DocumentEntity(
        id = 1, title = "t", emoji = "📄", sourceType = SourceType.URL, sourceRef = url, sourceLang = "", targetLang = "ru",
        status = DocStatus.QUEUED, progress = 0f, errorCode = null, errorMessage = null, direction = null, pipelineVersion = 0,
        briefJson = null, modelsSnapshotJson = null, promptTokens = 0, completionTokens = 0, costUsd = null, audioSeconds = 0,
        wordCount = 0, createdAt = 0, updatedAt = 0,
    )
}
