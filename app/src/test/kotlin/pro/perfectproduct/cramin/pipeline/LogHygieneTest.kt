package pro.perfectproduct.cramin.pipeline

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import pro.perfectproduct.cramin.app.HttpLogging
import pro.perfectproduct.cramin.data.repo.NewDocument
import pro.perfectproduct.cramin.llm.OpenRouterClient
import pro.perfectproduct.cramin.testing.FakeLlmClient
import pro.perfectproduct.cramin.testing.FakeOpenRouterServer
import pro.perfectproduct.cramin.testing.Fixtures
import pro.perfectproduct.cramin.testing.TestPipeline
import pro.perfectproduct.cramin.util.Lang
import pro.perfectproduct.cramin.util.Log

/**
 * SPEC §11: в лог не попадают ни ключ, ни заголовок Authorization, ни тексты документов и переводов.
 * Пайплайн идёт через настоящий OpenRouterClient с HTTP-логированием debug-сборки к фейковому серверу.
 */
@RunWith(RobolectricTestRunner::class)
class LogHygieneTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val lines = ArrayList<String>()
    private val server = FakeOpenRouterServer(FakeLlmClient())

    @After
    fun tearDown() {
        Log.install(null)
        server.close()
    }

    @Test
    fun pipelineLogsContainNoKeyNoTexts() = runTest {
        assertTrue("тест имеет смысл только в debug-сборке", Log.enabled)
        Log.install { level, tag, message, error -> lines += "$level $tag $message ${error?.message.orEmpty()}" }
        val http = OkHttpClient.Builder().apply { HttpLogging.install(this) }.build()
        val client = OpenRouterClient(http, keyProvider = { FakeOpenRouterServer.VALID_KEY }, baseUrl = server.baseUrl)
        TestPipeline(tmp.root, client).use { p ->
            val id = p.documents.create(NewDocument.Text(Fixtures.text(Lang.EN), null, Lang.RU, null))
            assertTrue(p.processor().process(id) is ProcessOutcome.Ready)
        }
        assertTrue("лог пуст — sink не подключён", lines.isNotEmpty())
        val joined = lines.joinToString("\n")
        assertFalse("ключ в логе", joined.contains(FakeOpenRouterServer.VALID_KEY.substring(0, 16)))
        assertFalse("Authorization в логе", joined.contains("Bearer "))
        // Ни одно предложение фикстуры (первые 5 слов) не должно попасть в лог.
        val fixtureSentences = Segmenter(Icu4jSentenceBreaker()).segment(Fixtures.text(Lang.EN), Lang.EN).map { it.text }
        for (s in fixtureSentences) {
            val probe = s.split(" ").take(5).joinToString(" ")
            assertFalse("текст документа в логе: $probe", joined.contains(probe))
        }
        assertFalse("перевод в логе", joined.contains("tr_library"))
        assertTrue("HTTP-лог уровня BASIC присутствует", lines.any { it.contains("--> POST") })
    }
}
