package pro.perfectproduct.cramin.llm

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import pro.perfectproduct.cramin.pipeline.Messages
import pro.perfectproduct.cramin.pipeline.SentenceDraft
import pro.perfectproduct.cramin.testing.FakeOpenRouterServer
import pro.perfectproduct.cramin.util.Lang

class OpenRouterClientTest {
    private val server = FakeOpenRouterServer()
    private val sleeps = ArrayList<Long>()
    private val client = OpenRouterClient(
        OkHttpClient(), keyProvider = { FakeOpenRouterServer.VALID_KEY }, baseUrl = server.baseUrl,
        sleeper = { sleeps += it },
    )
    private val request = LlmRequest(
        ModelRole.TRANSLATE, "fake/translate", Prompts.TRANSLATE,
        Messages.translate(Lang.EN, Lang.RU, null, emptyList(), emptyList(), listOf(SentenceDraft(0, 0, "Hello there."))),
        Schemas.TRANSLATE_NAME, Schemas.TRANSLATE, 0.3, 4000,
    )

    @org.junit.Before
    fun silenceLog() = pro.perfectproduct.cramin.util.Log.install { _, _, _, _ -> }

    @After
    fun tearDown() {
        server.close()
        pro.perfectproduct.cramin.util.Log.install(null)
    }

    @Test
    fun sendsStructuredOutputRequestAndParsesUsage() = runTest {
        val resp = client.complete(request)
        assertTrue(resp.content.contains("\"seg\""))
        assertEquals("stop", resp.finishReason)
        assertEquals(0.0001, resp.usage.costUsd!!, 1e-9)
        assertTrue(resp.usage.promptTokens > 0)
        val recorded = server.requests.single()
        assertEquals("Bearer ${FakeOpenRouterServer.VALID_KEY}", recorded.headers["Authorization"])
        assertEquals(OpenRouterClient.REFERER, recorded.headers["HTTP-Referer"])
        assertEquals("Cramin", recorded.headers["X-Title"])
        val body = Json.parseToJsonElement(recorded.body!!.utf8()).jsonObject
        assertEquals("json_schema", body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("true", body["response_format"]!!.jsonObject["json_schema"]!!.jsonObject["strict"].toString())
        assertEquals("true", body["usage"]!!.jsonObject["include"].toString())
        assertEquals("true", body["provider"]!!.jsonObject["require_parameters"].toString())
        assertEquals("4000", body["max_tokens"].toString())
        assertEquals("system", body["messages"]!!.let { Json.parseToJsonElement(it.toString()) }.let { (it as kotlinx.serialization.json.JsonArray)[0].jsonObject["role"]!!.jsonPrimitive.content })
    }

    @Test
    fun retriesOn429WithRetryAfterAnd5xx() = runTest {
        server.scripted += MockResponse(code = 429, headers = headersOf("Retry-After", "2"), body = "{}")
        server.scripted += MockResponse(code = 503, body = "{}")
        val resp = client.complete(request)
        assertEquals("stop", resp.finishReason)
        assertEquals(3, server.requests.size)
        assertEquals(listOf(2000L, 2000L), sleeps) // Retry-After 2 с, затем экспонента 2 с
    }

    @Test
    fun givesUpAfterFourAttempts() = runTest {
        repeat(4) { server.scripted += MockResponse(code = 500, body = "{}") }
        try {
            client.complete(request)
            fail("expected Server")
        } catch (e: LlmException.Server) {
            assertEquals(500, e.status)
        }
        assertEquals(4, server.requests.size)
        assertEquals(listOf(1000L, 2000L, 4000L), sleeps)
    }

    @Test
    fun authPaymentAndBadRequestAreNotRetried() = runTest {
        server.scripted += MockResponse(code = 401, body = """{"error":{"code":401,"message":"User not found."}}""")
        try { client.complete(request); fail() } catch (e: LlmException.Auth) { assertEquals(401, e.status) }
        server.scripted += MockResponse(code = 402, body = """{"error":{"code":402,"message":"Insufficient credits"}}""")
        try { client.complete(request); fail() } catch (e: LlmException.Payment) { /* ok */ }
        server.scripted += MockResponse(code = 400, body = """{"error":{"code":400,"message":"model not found"}}""")
        try { client.complete(request); fail() } catch (e: LlmException.BadRequest) { assertTrue(e.detail.contains("model not found")) }
        assertEquals(3, server.requests.size)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun providerErrorInside200Body() = runTest {
        server.scripted += MockResponse(body = """{"error":{"code":429,"message":"provider overloaded"}}""")
        val resp = client.complete(request)
        assertEquals("stop", resp.finishReason)
        assertEquals(2, server.requests.size)
    }

    @Test
    fun finishReasonLengthIsPassedThrough() = runTest {
        server.scripted += MockResponse(body = """{"choices":[{"finish_reason":"length","message":{"role":"assistant","content":"{\"seg\":["}}],"usage":{"prompt_tokens":5,"completion_tokens":9}}""")
        val resp = client.complete(request)
        assertTrue(resp.truncated)
        assertNull(resp.usage.costUsd)
    }

    @Test
    fun missingKeyIsAuthZero() = runTest {
        val noKey = OpenRouterClient(OkHttpClient(), keyProvider = { null }, baseUrl = server.baseUrl)
        try { noKey.complete(request); fail() } catch (e: LlmException.Auth) { assertEquals(0, e.status) }
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun keyCheckerAndCatalog() = runTest {
        val checker = KeyChecker(OkHttpClient(), server.baseUrl)
        val valid = checker.check(FakeOpenRouterServer.VALID_KEY)
        assertTrue(valid is KeyCheck.Valid && valid.limitRemainingUsd == 9.75)
        assertEquals(KeyCheck.Invalid, checker.check("sk-or-v1-wrong"))

        val models = ModelCatalog.parse(FakeOpenRouterServer.CATALOG_JSON)
        assertEquals(5, models.size)
        val t = models.first { it.id == "fake/translate" }
        assertEquals(8000, t.maxCompletionTokens)
        assertTrue(t.supportsStructuredOutputs)
        assertEquals(1.0, t.promptPricePerMillion!!, 1e-9)
        assertTrue(!models.first { it.id == "fake/no-structured" }.supportsStructuredOutputs)
    }
}
