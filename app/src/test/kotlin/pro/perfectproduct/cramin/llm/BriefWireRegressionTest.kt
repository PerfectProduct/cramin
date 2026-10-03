package pro.perfectproduct.cramin.llm

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import mockwebserver3.MockResponse
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import pro.perfectproduct.cramin.pipeline.*
import pro.perfectproduct.cramin.testing.FakeOpenRouterServer
import pro.perfectproduct.cramin.util.Lang

class BriefWireRegressionTest {
    private fun request(text: String) = LlmRequest(ModelRole.BRIEF, "fake/brief", Prompts.BRIEF,
        Messages.brief(Lang.RU, Lang.EN, text), Schemas.BRIEF_NAME, Schemas.BRIEF, 0.2, 6000,
        buildJsonObject { put("effort", "none") })

    @Test fun configuredSmallWordLimitIsHonouredOnWire() = runTest {
        val input = BriefInput.select(List(4) { "берег ".repeat(8) }, 10)
        FakeOpenRouterServer().use { server ->
            val client = OpenRouterClient(OkHttpClient(), { FakeOpenRouterServer.VALID_KEY }, server.baseUrl)
            client.complete(request(input))
            val wire = Json.parseToJsonElement(server.requests.single().body!!.utf8()).jsonObject
            val user = wire["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonPrimitive.content
            assertTrue(user.contains(input))
            assertTrue("Selected input must obey configured word cap", WordCounter.count(input) <= 10)
        }
    }

    @Test fun oversizedSingleParagraphIsNotReplacedByEmptyBrief() = runTest {
        val input = BriefInput.select(listOf("достопримечательности ".repeat(1000)), 100)
        FakeOpenRouterServer().use { server ->
            OpenRouterClient(OkHttpClient(), { FakeOpenRouterServer.VALID_KEY }, server.baseUrl).complete(request(input))
            assertTrue("Nonempty source must produce nonempty brief sample", input.isNotBlank())
            assertTrue(WordCounter.count(input) <= 100)
        }
    }

    @Test fun typedContextFailureSurvivesWithoutProviderMessageAndWithoutRetry() = runTest {
        FakeOpenRouterServer().use { server ->
            server.scripted += MockResponse(code = 400, body = """{"error":{"code":400,"message":"PRIVATE DOCUMENT AND KEY","metadata":{"error_type":"context_length_exceeded","raw":"PRIVATE"}}}""")
            val error = try {
                OpenRouterClient(OkHttpClient(), { FakeOpenRouterServer.VALID_KEY }, server.baseUrl).complete(request("Короткий русский текст."))
                error("Expected rejection")
            } catch (e: LlmException.BadRequest) { e }
            assertEquals("CONTEXT_LENGTH", error.detail)
            assertFalse(error.message!!.contains("PRIVATE"))
            assertEquals(1, server.requests.size)
        }
    }
    @Test fun actualWireEventIsAllowlistedAndDistinguishesHttpFromApiStatus() = runTest {
        FakeOpenRouterServer().use { server ->
            server.scripted += MockResponse(code = 200, body = """{"id":"gen-1700000000-AbCdEfGh1234","model":"fake/brief","provider":"OpenAI","error":{"code":400,"param":"temperature","message":"PRIVATE","metadata":{"provider_code":"unsupported_parameter","limit":16000,"raw":"PRIVATE"}}}""")
            var persisted: RequestDiagnostic? = null
            val r = request("PRIVATE русский текст").copy(parametersFrozen = true,
                supportedParameters = setOf("reasoning", "max_tokens", "structured_outputs"),
                configOrigin = ConfigOrigin.LEGACY_FALLBACK, onFailureDiagnostic = { persisted = it })
            val error = try {
                OpenRouterClient(OkHttpClient(), { FakeOpenRouterServer.VALID_KEY }, server.baseUrl).complete(r)
                error("Expected rejection")
            } catch (e: LlmException.BadRequest) { e }
            val event = requireNotNull(persisted)
            assertEquals(event, error.diagnostic)
            assertEquals(200, event.httpStatus)
            assertEquals(400, event.apiStatus)
            assertEquals(RequestRejection.UNSUPPORTED_PARAMETER, event.rejection)
            assertEquals("temperature", event.parameter)
            assertEquals("NOT_SENT", event.temperature)
            assertEquals("6000", event.maxTokens)
            assertEquals("none", event.reasoning!!["effort"]!!.jsonPrimitive.content)
            assertEquals("OpenAI", event.provider)
            assertEquals(16000L, event.reportedLimits["limit"])
            assertTrue(event.respondedAtEpochMs!! >= event.startedAtEpochMs)
            assertEquals(64, event.schemaSha256.length)
            assertFalse(event.copyText().contains("PRIVATE"))
            assertEquals(1, server.requests.size)
            val wire = server.requests.single().body!!.utf8()
            assertEquals(wire.toByteArray(Charsets.UTF_8).size, event.serializedBytes)
            assertTrue(wire.contains("require_parameters"))
            assertTrue(wire.contains("json_schema"))
        }
    }

    @Test fun machineReasonsAreDistinctButProseNeverGuessedOrRetried() = runTest {
        val cases = mapOf("context_length_exceeded" to RequestRejection.CONTEXT_LENGTH,
            "invalid_json_schema" to RequestRejection.INVALID_SCHEMA,
            "model_not_found" to RequestRejection.MODEL_OR_ROUTE,
            "max_tokens_exceeded" to RequestRejection.OUTPUT_LIMIT,
            "unmapped" to RequestRejection.UNKNOWN)
        for ((code, expected) in cases) FakeOpenRouterServer().use { server ->
            server.scripted += MockResponse(code = 400, body = """{"error":{"code":400,"message":"context exceeded PRIVATE","param":"PRIVATE","metadata":{"provider_code":"$code","provider_name":"PRIVATE","raw":"PRIVATE","limit":"PRIVATE"}}}""")
            val error = try { OpenRouterClient(OkHttpClient(), { FakeOpenRouterServer.VALID_KEY }, server.baseUrl).complete(request("текст")); error("Expected failure") }
                catch (e: LlmException.BadRequest) { e }
            assertEquals(expected, error.category)
            assertNull(error.diagnostic!!.provider)
            assertNull(error.diagnostic!!.parameter)
            assertFalse(error.diagnostic!!.copyText().contains("PRIVATE"))
            assertEquals(1, server.requests.size)
        }
    }

    @Test fun nestedNativeMachineCodeIsExtractedWithoutRawMessage() = runTest {
        FakeOpenRouterServer().use { server ->
            val native = buildJsonObject { put("error", buildJsonObject {
                put("code", "unsupported_parameter"); put("param", "temperature"); put("message", "PRIVATE")
            }) }
            val envelope = buildJsonObject { put("error", buildJsonObject {
                put("code", 400); put("message", "Provider returned error")
                put("metadata", buildJsonObject { put("raw", native.toString()) })
            }) }
            server.scripted += MockResponse(code = 400, body = envelope.toString())
            val error = try { OpenRouterClient(OkHttpClient(), { FakeOpenRouterServer.VALID_KEY }, server.baseUrl).complete(request("текст")); error("Expected failure") }
                catch (e: LlmException.BadRequest) { e }
            assertEquals(RequestRejection.UNSUPPORTED_PARAMETER, error.category)
            assertEquals("temperature", error.diagnostic!!.parameter)
            assertFalse(error.diagnostic!!.copyText().contains("PRIVATE"))
        }
    }

}
