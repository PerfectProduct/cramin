package pro.perfectproduct.cramin.chatgpt

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okio.Buffer
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pro.perfectproduct.cramin.llm.*
import pro.perfectproduct.cramin.pipeline.Messages
import pro.perfectproduct.cramin.pipeline.SentenceDraft
import pro.perfectproduct.cramin.util.Lang
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ResponsesLlmClientTest {
    private val model = AccountModel.fromCatalog(Json.parseToJsonElement("""{"models":[{"slug":"synthetic-model","display_name":"Synthetic","visibility":"list"}]}""").jsonObject).single()
    private val request = LlmRequest(ModelRole.TRANSLATE, model.slug, Prompts.TRANSLATE,
        Messages.translate(Lang.EN, Lang.RU, null, emptyList(), emptyList(), listOf(SentenceDraft(0, 0, "Hello there."))),
        Schemas.TRANSLATE_NAME, Schemas.TRANSLATE, 0.3, 4000,
        reasoning = buildJsonObject { put("max_tokens", 200) })
    private val valid = """{"seg":[{"from":0,"to":0,"t":"Привет.","sourceIds":["s0"]}]}"""
    private fun event(value: JsonObject) = "event: ${value.string("type")}\ndata: $value\n\n"
    private fun completed(text: String = valid, status: String = "completed", refusal: Boolean = false, actualModel: String = model.slug) = event(buildJsonObject {
        put("type", "response.completed")
        putJsonObject("response") {
            put("status", status); put("model", actualModel)
            putJsonArray("output") { addJsonObject {
                put("type", "message"); put("role", "assistant"); put("status", "completed")
                putJsonArray("content") { addJsonObject {
                    put("type", if (refusal) "refusal" else "output_text")
                    put(if (refusal) "refusal" else "text", text)
                } }
            } }
            putJsonObject("usage") { put("input_tokens", 12); put("output_tokens", 9) }
        }
    })
    private val partial = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"{\"}\n\n"
    private fun failed(code: String) = "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"$code\"}}}\n\n"
    private fun parse(s: String) = ResponsesLlmClient.consume(Buffer().writeUtf8(s), request)
    private inline fun failure(kind: ResponseFailureKind, block: () -> Unit): ResponseFailure {
        try { block(); error("expected failure") } catch (e: ResponseFailure) { assertEquals(kind, e.kind); return e }
    }
    private fun session(scopes: Set<String> = REQUESTED_SCOPES, expires: Long = System.currentTimeMillis()/1000 + 3600) = AccessSession("synthetic-access", scopes, expires)

    @Test fun realCraminSchemaAndPromptAreMappedWithoutProviderFields() {
        val body = ResponsesLlmClient.buildBody(request, model)
        assertNotNull(request.temperature); assertNotNull(request.maxTokens)
        assertChatGptPlanHttpBody(body)
        assertEquals(JsonPrimitive(false), body["store"]); assertEquals(JsonPrimitive(true), body["stream"])
        assertEquals(request.system, body.string("instructions"))
        val input = body.getValue("input").jsonArray.single().jsonObject
        assertEquals("user", input.string("role")); assertEquals(request.user, input.string("content"))
        val format = body.getValue("text").jsonObject.getValue("format").jsonObject
        assertEquals(Schemas.TRANSLATE, format["schema"]); assertEquals("json_schema", format.string("type"))
        assertEquals(JsonPrimitive(true), format["strict"])
    }
    @Test fun allCraminSchemasFitPrototypeValidator() {
        listOf(Schemas.BRIEF, Schemas.TRANSLATE, Schemas.EXTRACT, Schemas.CONSOLIDATE, TopicCategories.schema, TopicCategories.consolidationSchema).forEach(PrototypeSchema::checkSchema)
    }
    @Test fun completionIsRequiredAndCostRemainsUnknown() {
        val response = parse(": keepalive\r\n\r\n" + partial + completed())
        assertEquals(valid, response.content); assertEquals(12, response.usage.promptTokens); assertNull(response.usage.costUsd)
        failure(ResponseFailureKind.INTERRUPTED) { parse(partial) }
        failure(ResponseFailureKind.INTERRUPTED) { parse(partial + "data: [DONE]\n\n") }
    }
    @Test fun invalidSchemaAndInvalidCompletedStatusAreRejected() {
        for (text in listOf("{}", "not json", """{"seg":[{"from":"0","to":0,"t":"x","sourceIds":[]}]}""", """{"seg":[],"extra":true}"""))
            failure(ResponseFailureKind.INVALID_RESULT) { parse(completed(text)) }
        failure(ResponseFailureKind.INVALID_RESULT) { parse(completed(status = "in_progress")) }
    }
    @Test fun limitErrorsAfterPartialTextNeverReturnSuccess() {
        val exceeded = failure(ResponseFailureKind.USAGE_LIMIT) { parse(partial + failed("subscription_sharing_usage_limit_exceeded")) }
        assertEquals("subscription_sharing_usage_limit_exceeded", exceeded.code)
        failure(ResponseFailureKind.USAGE_UNAVAILABLE) { parse(partial + failed("subscription_sharing_usage_unavailable")) }
    }
    @Test fun refusalFailedIncompleteAndExplicitErrorAreDistinct() {
        failure(ResponseFailureKind.REFUSAL) { parse(completed(refusal = true)) }
        failure(ResponseFailureKind.REFUSAL) { parse("data: {\"type\":\"response.refusal.delta\",\"delta\":\"no\"}\n\n") }
        failure(ResponseFailureKind.FAILED) { parse(failed("server_error")) }
        failure(ResponseFailureKind.INCOMPLETE) { parse("data: {\"type\":\"response.incomplete\"}\n\n") }
        failure(ResponseFailureKind.USAGE_LIMIT) { parse("data: {\"type\":\"error\",\"code\":\"subscription_sharing_usage_limit_exceeded\"}\n\n") }
    }
    @Test fun multilineSseAndCrlfWorkAndUnterminatedCompletionFails() {
        val payload = completed().substringAfter("data: ").trim()
        assertEquals(valid, parse("data: ${payload.substringBefore(',')},\r\ndata: ${payload.substringAfter(',')}\r\n\r\n").content)
        failure(ResponseFailureKind.INTERRUPTED) { parse(completed().trimEnd()) }
    }
    @Test fun openRouterModelCannotBeSilentlyMapped() {
        try { ResponsesLlmClient.buildBody(request.copy(model = "openai/synthetic-model"), model); fail("model accepted") }
        catch (_: IllegalArgumentException) {}
    }
    @Test fun missingScopesAndExpiredSessionPreventTransport() {
        for (scope in REQUESTED_SCOPES) {
            try { session(REQUESTED_SCOPES - scope).requireUsable(); fail("missing scope accepted") } catch (_: IllegalStateException) {}
        }
        try { session(expires = 1).requireUsable(); fail("expired accepted") } catch (_: IllegalStateException) {}
    }
    @Test fun httpRequestUsesOAuthAndHandlesEarlyAdmissionWithoutFallback() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(headers = headersOf("Content-Type", "text/event-stream"), body = completed()))
            val client = ResponsesLlmClient({ session() }, model, endpoint = server.url("/v1/responses").toString())
            assertEquals(valid, client.complete(request).content)
            val recorded = server.takeRequest()
            assertEquals("Bearer synthetic-access", recorded.headers["Authorization"])
            assertEquals("/v1/responses", recorded.url.encodedPath)
            assertNull(recorded.headers["HTTP-Referer"]); assertNull(recorded.headers["X-Title"])
            assertChatGptPlanHttpBody(Json.parseToJsonElement(recorded.body!!.utf8()).jsonObject)
            server.enqueue(MockResponse(code = 403, headers = headersOf("x-request-id", "synthetic-id"), body = """{"detail":"not admitted"}"""))
            val failure = try { client.complete(request); error("expected HTTP failure") } catch (e: ResponseFailure) { e }
            assertEquals(ResponseFailureKind.HTTP, failure.kind); assertEquals(403, failure.httpStatus)
            assertEquals("synthetic-id", failure.requestId); assertEquals(emptySet<String>(), failure.bodyKeys)
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun nonNullSamplingAndTokenCapsAreOmittedFromSerializedHttpBody() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val client = ResponsesLlmClient({ session() }, model, endpoint = server.url("/v1/responses").toString())
            for ((temperature, maxTokens) in listOf(0.3 to 4000, 0.7 to 512)) {
                server.enqueue(MockResponse(headers = headersOf("Content-Type", "text/event-stream"), body = completed()))
                assertEquals(valid, client.complete(request.copy(temperature = temperature, maxTokens = maxTokens)).content)
                val recorded = server.takeRequest()
                assertEquals("POST", recorded.method)
                assertChatGptPlanHttpBody(Json.parseToJsonElement(recorded.body!!.utf8()).jsonObject)
            }
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun transportCancellationPropagatesAndDoesNotRetry() = runBlocking {
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5000
            val streaming = CompletableDeferred<Unit>()
            val peer = async(Dispatchers.IO) {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream()
                    val headers = StringBuilder()
                    while (!headers.endsWith("\r\n\r\n")) {
                        val byte = input.read(); check(byte >= 0); headers.append(byte.toChar())
                    }
                    val length = headers.toString().split("\r\n")
                        .first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                    repeat(length) { check(input.read() >= 0) }
                    val body = partial.toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray())
                        write("${body.size.toString(16)}\r\n".toByteArray()); write(body); write("\r\n".toByteArray()); flush()
                    }
                    streaming.complete(Unit)
                    // Cancellation must close the in-progress socket, not wait for the stream timeout.
                    try { assertEquals(-1, input.read()) } catch (_: java.net.SocketException) {}
                }
            }
            val client = ResponsesLlmClient({ session() }, model,
                endpoint = "http://127.0.0.1:${server.localPort}/v1/responses")
            val task = async { client.complete(request) }
            withTimeout(5000) { streaming.await() }
            task.cancel()
            try { withTimeout(5000) { task.await() }; fail("cancel swallowed") } catch (_: CancellationException) {}
            withTimeout(5000) { peer.await() }
        }
    }

    private fun assertDiagnostic(e: ResponseFailure, stage: ResponseStage, terminal: TerminalEvent? = null): JsonObject {
        assertNotNull(e.diagnostic)
        assertEquals(stage, e.diagnostic!!.stage)
        assertEquals(terminal, e.diagnostic.terminalEvent)
        val json = e.diagnostic.toJson()
        assertEquals(if (terminal == null) "not_observed" else "received_rejected", json.string("terminal_observation"))
        return json
    }

    @Test fun sseMediaTypeIsCaseInsensitiveAndAcceptsParameters() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            for (media in listOf("text/event-stream; charset=utf-8", "Text/Event-Stream; Charset=\"utf-8\"", "TEXT/EVENT-STREAM; x-trace=synthetic-secret")) {
                server.enqueue(MockResponse(headers = headersOf("Content-Type", media), body = completed()))
                val client = ResponsesLlmClient({ session() }, model, endpoint = server.url("/responses").toString())
                assertEquals(valid, client.complete(request).content)
                assertEquals("received_validated", client.completionEvidence!!.string("terminal_observation"))
            }
            assertEquals(3, server.requestCount)
        }
    }

    private fun assertMissingSseRoute(diagnostic: JsonObject) {
        assertEquals(JsonPrimitive(200), diagnostic["http_status"])
        assertEquals(JsonNull, diagnostic["media_type"])
        assertEquals("missing", diagnostic.string("media_type_state"))
        assertEquals(JsonPrimitive(true), diagnostic["sse_parser_without_content_type"])
        assertNull(diagnostic["body"]) // No consuming inspection before the SSE parser.
    }

    @Test fun missingContentTypeAllowsCompletedBodyThroughStrictParser() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse(body = completed()))
            val client = ResponsesLlmClient({ session() }, model, endpoint = server.url("/responses").toString())
            assertEquals(valid, client.complete(request).content)
            val evidence = client.completionEvidence!!
            assertEquals("response.completed", evidence.string("event"))
            assertEquals("completed", evidence.string("status"))
            val d = evidence["diagnostic"]!!.jsonObject
            assertMissingSseRoute(d)
            assertEquals("response_completed", d.string("stage"))
            assertEquals("received_validated", d.string("terminal_observation"))
            assertEquals("completed", d.string("terminal_status"))
            assertChatGptPlanHttpBody(Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun missingContentTypeAcceptsSameStreamAcrossNetworkAndUtf8Fragments() = runBlocking {
        // Cross both SSE-line boundaries and multibyte Cyrillic characters, including HTTP chunks of one byte.
        val stream = "event: response.created\ndata: {\"type\":\"response.created\"}\n\n" + partial + completed()
        MockWebServer().use { server ->
            server.start()
            for (chunkSize in listOf(1, 7, 31)) {
                server.enqueue(MockResponse.Builder().chunkedBody(stream, chunkSize)
                    .throttleBody(19, 1, TimeUnit.MILLISECONDS).build())
                val client = ResponsesLlmClient({ session() }, model, endpoint = server.url("/responses").toString())
                assertEquals(valid, client.complete(request).content)
                assertMissingSseRoute(client.completionEvidence!!["diagnostic"]!!.jsonObject)
            }
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun missingContentTypePreservesFirstTerminalAndFirstMalformedFrameBeyondInspectorLimit() = runBlocking {
        // If 16 KiB were consumed up front, the first terminal/error would be lost and the later
        // completed frame could incorrectly determine the result. The intervening comment is >16 KiB.
        val padding = ": " + "x".repeat(20_000) + "\n\n"
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = completed() + padding + completed("{}")))
            val accepted = ResponsesLlmClient({ session() }, model, endpoint = server.url("/responses").toString())
            assertEquals(valid, accepted.complete(request).content)
            assertMissingSseRoute(accepted.completionEvidence!!["diagnostic"]!!.jsonObject)
            for ((first, kind, stage, terminal) in listOf(
                FirstFrame(failed("server_error"), ResponseFailureKind.FAILED, ResponseStage.RESPONSE_FAILED, TerminalEvent.FAILED),
                FirstFrame("data: not-json\n\n", ResponseFailureKind.INVALID_RESULT, ResponseStage.SSE_FRAMING),
            )) {
                server.enqueue(MockResponse(body = first + padding + completed()))
                val rejected = ResponsesLlmClient({ session() }, model, endpoint = server.url("/responses").toString())
                val e = failure(kind) { rejected.complete(request) }
                assertMissingSseRoute(assertDiagnostic(e, stage, terminal))
                assertNull(rejected.completionEvidence)
            }
            assertEquals(3, server.requestCount)
        }
    }

    private data class FirstFrame(val body: String, val kind: ResponseFailureKind, val stage: ResponseStage,
                                  val terminal: TerminalEvent? = null)

    @Test fun missingContentTypeRejectsHtmlJsonMalformedPartialFailedIncompleteAndInvalidResults() = runBlocking {
        val secret = "sensitive-missing-content-type-body"
        val jsonResponse = Json.parseToJsonElement(completed().substringAfter("data: ").trim()).jsonObject["response"]!!
        val fixtures = listOf(
            FirstFrame("<html><body>$secret</body></html>\n\n", ResponseFailureKind.INTERRUPTED, ResponseStage.NO_TERMINAL_EVENT),
            FirstFrame(jsonResponse.toString() + "\n\n", ResponseFailureKind.INTERRUPTED, ResponseStage.NO_TERMINAL_EVENT),
            FirstFrame("data: $secret\n\n", ResponseFailureKind.INVALID_RESULT, ResponseStage.SSE_FRAMING),
            FirstFrame("data: {\"type\":\"random\"}\n\n", ResponseFailureKind.INTERRUPTED, ResponseStage.NO_TERMINAL_EVENT),
            FirstFrame("", ResponseFailureKind.INTERRUPTED, ResponseStage.NO_TERMINAL_EVENT),
            FirstFrame(partial, ResponseFailureKind.INTERRUPTED, ResponseStage.NO_TERMINAL_EVENT),
            FirstFrame(partial + "data: [DONE]\n\n", ResponseFailureKind.INTERRUPTED, ResponseStage.NO_TERMINAL_EVENT),
            FirstFrame(completed().trimEnd(), ResponseFailureKind.INTERRUPTED, ResponseStage.SSE_FRAMING),
            FirstFrame(failed("server_error"), ResponseFailureKind.FAILED, ResponseStage.RESPONSE_FAILED, TerminalEvent.FAILED),
            FirstFrame(failed("subscription_sharing_usage_limit_exceeded"), ResponseFailureKind.USAGE_LIMIT, ResponseStage.RESPONSE_FAILED, TerminalEvent.FAILED),
            FirstFrame("data: {\"type\":\"response.incomplete\",\"response\":{\"status\":\"incomplete\"}}\n\n",
                ResponseFailureKind.INCOMPLETE, ResponseStage.RESPONSE_INCOMPLETE, TerminalEvent.INCOMPLETE),
            FirstFrame(completed(status = "in_progress"), ResponseFailureKind.INVALID_RESULT, ResponseStage.TERMINAL_STATUS, TerminalEvent.COMPLETED),
            FirstFrame(completed(actualModel = secret), ResponseFailureKind.INVALID_RESULT, ResponseStage.MODEL_MISMATCH, TerminalEvent.COMPLETED),
            FirstFrame(completed(secret), ResponseFailureKind.INVALID_RESULT, ResponseStage.JSON_PARSE, TerminalEvent.COMPLETED),
            FirstFrame(completed("{}"), ResponseFailureKind.INVALID_RESULT, ResponseStage.SCHEMA_VALIDATION, TerminalEvent.COMPLETED),
        )
        MockWebServer().use { server ->
            server.start()
            for (fixture in fixtures) {
                server.enqueue(MockResponse(headers = headersOf("Set-Cookie", secret), body = fixture.body))
                val client = ResponsesLlmClient({ session() }, model, endpoint = server.url("/responses").toString())
                val e = failure(fixture.kind) { client.complete(request) }
                val d = assertDiagnostic(e, fixture.stage, fixture.terminal)
                assertMissingSseRoute(d)
                assertFalse(d.toString().contains(secret)); assertFalse(d.toString().contains("<html>"))
                assertFalse(d.toString().contains("Привет")); assertFalse(d.toString().contains("Set-Cookie"))
                assertNull(client.completionEvidence)
            }
            assertEquals(fixtures.size, server.requestCount) // One POST per manual call, no retry.
        }
    }

    @Test fun missingContentTypeExceptionLeavesCorrectAndExplicitlyWrongMediaBehaviorUnchanged() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val validHeaders = listOf("text/event-stream", "Text/Event-Stream; Charset=\"utf-8\"")
            for (header in validHeaders) {
                server.enqueue(MockResponse(headers = headersOf("Content-Type", header), body = completed()))
                val client = ResponsesLlmClient({ session() }, model, endpoint = server.url("/responses").toString())
                assertEquals(valid, client.complete(request).content)
                val d = client.completionEvidence!!["diagnostic"]!!.jsonObject
                assertEquals("parsed", d.string("media_type_state")); assertEquals("text/event-stream", d.string("media_type"))
                assertEquals(JsonPrimitive(false), d["sse_parser_without_content_type"])
                assertEquals("received_validated", d.string("terminal_observation"))
            }
            val invalidHeaders = listOf(
                headersOf("Content-Type", "application/json"),
                headersOf("Content-Type", "text/html"),
                headersOf("Content-Type", "text/plain"),
                headersOf("Content-Type", "not a media type sensitive-header"),
                headersOf("Content-Type", "text/event-stream", "Content-Type", "text/event-stream"),
                headersOf("Content-Type", "text/event-stream", "Content-Type", "application/json"),
                headersOf("Content-Type", "application/sensitive-header"),
            )
            for (headers in invalidHeaders) {
                server.enqueue(MockResponse(headers = headers, body = completed()))
                val client = ResponsesLlmClient({ session() }, model, endpoint = server.url("/responses").toString())
                val e = failure(ResponseFailureKind.INVALID_RESULT) { client.complete(request) }
                val d = assertDiagnostic(e, ResponseStage.CONTENT_TYPE)
                assertEquals(JsonPrimitive(false), d["sse_parser_without_content_type"])
                assertNotEquals("missing", d.string("media_type_state"))
                assertEquals("sse_like", d["body"]!!.jsonObject.string("classification"))
                assertFalse(d.toString().contains("sensitive-header")); assertNull(client.completionEvidence)
            }
            assertEquals(validHeaders.size + invalidHeaders.size, server.requestCount)
        }
    }

    @Test fun missingContentTypeOnHttpErrorDoesNotInvokeSseParser() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse(code = 503, headers = headersOf("Retry-After", "0"), body = completed()))
            val client = ResponsesLlmClient({ session() }, model, endpoint = server.url("/responses").toString())
            val e = failure(ResponseFailureKind.HTTP) { client.complete(request) }
            val d = assertDiagnostic(e, ResponseStage.HTTP)
            assertEquals("missing", d.string("media_type_state"))
            assertEquals(JsonPrimitive(false), d["sse_parser_without_content_type"])
            assertEquals(JsonPrimitive(503), d["http_status"])
            assertNull(client.completionEvidence); assertEquals(1, server.requestCount)
        }
    }

    @Test fun completedJsonObjectDoesNotBypassStreamingContractAndNeverPersistsContent() = runBlocking {
        val secret = "sensitive-content-not-for-diagnostics"
        val response = Json.parseToJsonElement(completed().substringAfter("data: ").trim()).jsonObject["response"]!!.jsonObject
        val raw = JsonObject(response + mapOf("object" to JsonPrimitive("response"), "model" to JsonPrimitive(secret),
            "id" to JsonPrimitive(secret), secret to JsonPrimitive(secret))).toString()
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse(headers = headersOf("Content-Type", "Application/JSON; x-token=$secret", "Set-Cookie", secret), body = raw))
            val client = ResponsesLlmClient({ session() }, model, endpoint = server.url("/responses").toString())
            val e = failure(ResponseFailureKind.INVALID_RESULT) { client.complete(request) }
            val d = assertDiagnostic(e, ResponseStage.CONTENT_TYPE)
            val body = d["body"]!!.jsonObject
            assertEquals("application/json", d.string("media_type"))
            assertEquals("json", body.string("classification")); assertEquals("completed", body.string("response_status"))
            assertEquals(JsonPrimitive(true), body["response_object"]); assertEquals(JsonPrimitive(true), body["has_output_array"])
            assertFalse(d.toString().contains(secret)); assertFalse(d.toString().contains("Привет"))
            assertFalse(d.toString().contains("input_tokens")); assertNull(client.completionEvidence)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun jsonErrorDiagnosticsOnlyRetainAllowlistedCodesAndKeys() = runBlocking {
        val secret = "sensitive-error-message-or-key"
        MockWebServer().use { server ->
            server.start()
            for (code in listOf("subscription_sharing_usage_limit_exceeded", secret)) {
                val raw = """{"error":{"code":"$code","message":"$secret","param":"$secret"},"$secret":"$secret"}"""
                server.enqueue(MockResponse(headers = headersOf("Content-Type", "application/problem+json"), body = raw))
                val e = failure(ResponseFailureKind.INVALID_RESULT) {
                    ResponsesLlmClient({ session() }, model, endpoint = server.url("/responses").toString()).complete(request)
                }
                val d = assertDiagnostic(e, ResponseStage.CONTENT_TYPE)
                assertEquals(JsonPrimitive(200), d["http_status"])
                val shape = d["body"]!!.jsonObject
                assertEquals(JsonPrimitive(true), shape["has_error_object"])
                assertEquals(if (code == secret) null else code, shape.string("known_error_code"))
                assertFalse(d.toString().contains(secret))
                assertEquals(JsonArray(listOf(JsonPrimitive("error"))), shape["root_keys"])
            }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun htmlAndSseLikeBodiesWithUnexpectedMediaStayRejectedWithoutContent() = runBlocking {
        val secret = "sensitive-html-cookie"
        MockWebServer().use { server ->
            server.start()
            for ((media, raw, classification) in listOf(
                Triple("Text/HTML; charset=utf-8", "<!DOCTYPE html><html><body>$secret</body></html>", "html"),
                Triple("text/plain", completed(), "sse_like"),
                Triple("application/octet-stream", secret, "unknown"),
            )) {
                server.enqueue(MockResponse(headers = headersOf("Content-Type", media, "Set-Cookie", secret), body = raw))
                val e = failure(ResponseFailureKind.INVALID_RESULT) {
                    ResponsesLlmClient({ session() }, model, endpoint = server.url("/responses").toString()).complete(request)
                }
                val d = assertDiagnostic(e, ResponseStage.CONTENT_TYPE)
                assertEquals(classification, d["body"]!!.jsonObject.string("classification"))
                assertFalse(d.toString().contains(secret)); assertFalse(d.toString().contains("<!DOCTYPE"))
                assertFalse(d.toString().contains("Привет")); assertFalse(d.toString().contains("Set-Cookie"))
            }
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun malformedDuplicateAndUnlistedMediaDoNotRetainHeaderValues() {
        val secret = "sensitive-value"
        for ((headers, expected) in listOf(
            listOf("not a media type $secret") to "invalid",
            listOf("text/event-stream", "application/json") to "invalid",
            listOf("application/$secret; token=$secret") to "unlisted",
        )) {
            val media = WireMedia.parse(headers)
            assertFalse(media.isSse); assertEquals(expected, media.state)
            val d = ResponseDiagnostic(ResponseStage.CONTENT_TYPE, media = media).toJson()
            assertFalse(d.toString().contains(secret))
        }
        assertEquals("text/event-stream", WireMedia.parse(listOf("TEXT/EVENT-STREAM; charset=utf-8")).normalized)
    }

    @Test fun bodyInspectionIsBoundedAndDoesNotParseTruncatedOrDeepJson() {
        val source = Buffer().writeUtf8("{\"secret\":\"" + "x".repeat(40_000) + "\"}")
        val before = source.size
        val shape = ResponseBodyInspector.inspect(source)
        assertEquals(ResponseBodyInspector.PREFIX_LIMIT, before - source.size)
        assertEquals("json", shape.string("classification")); assertEquals("prefix_limit", shape.string("read_status"))
        assertEquals("not_attempted_truncated", shape.string("json_parse"))
        assertNull(shape["root_keys"]); assertFalse(shape.toString().contains("secret"))
        val deep = ResponseBodyInspector.inspect(Buffer().writeUtf8("[".repeat(100) + "0" + "]".repeat(100)))
        assertEquals("not_attempted_depth_limit", deep.string("json_parse")); assertNull(deep["json_root"])
    }

    @Test fun invalidResultsHaveDistinctStagesAndTerminalObservation() {
        for ((sse, stage) in listOf(
            "data: not-json\n\n" to ResponseStage.SSE_FRAMING,
            completed(status = "in_progress") to ResponseStage.TERMINAL_STATUS,
            completed(actualModel = "sensitive-other-model") to ResponseStage.MODEL_MISMATCH,
            completed("not JSON") to ResponseStage.JSON_PARSE,
            completed("not-json") to ResponseStage.JSON_PARSE,
            completed("{}") to ResponseStage.SCHEMA_VALIDATION,
        )) {
            val e = failure(ResponseFailureKind.INVALID_RESULT) { parse(sse) }
            val terminal = if (stage == ResponseStage.SSE_FRAMING) null else TerminalEvent.COMPLETED
            val d = assertDiagnostic(e, stage, terminal)
            assertFalse(d.toString().contains("sensitive-other-model"))
            assertFalse(d.toString().contains("not JSON"))
            assertFalse(safeProbeFailure(e).contains("HTTP 200;"))
        }
        val mismatchedFrame = completed().replace("event: response.completed", "event: sensitive-name")
        val e = failure(ResponseFailureKind.INVALID_RESULT) { parse(mismatchedFrame) }
        assertDiagnostic(e, ResponseStage.SSE_FRAMING, TerminalEvent.COMPLETED)
        assertFalse(e.diagnostic!!.toJson().toString().contains("sensitive-name"))
    }

    @Test fun absentTerminalAndRejectedTerminalAreNotConfused() {
        for (sse in listOf(partial, partial + "data: [DONE]\n\n")) {
            val e = failure(ResponseFailureKind.INTERRUPTED) { parse(sse) }
            assertDiagnostic(e, ResponseStage.NO_TERMINAL_EVENT)
        }
        val torn = failure(ResponseFailureKind.INTERRUPTED) { parse(completed().trimEnd()) }
        assertDiagnostic(torn, ResponseStage.SSE_FRAMING)
        val failed = failure(ResponseFailureKind.FAILED) { parse(failed("server_error")) }
        assertDiagnostic(failed, ResponseStage.RESPONSE_FAILED, TerminalEvent.FAILED)
        val incomplete = failure(ResponseFailureKind.INCOMPLETE) { parse("data: {\"type\":\"response.incomplete\"}\n\n") }
        assertDiagnostic(incomplete, ResponseStage.RESPONSE_INCOMPLETE, TerminalEvent.INCOMPLETE)
    }

    @Test fun sseFailureDiagnosticsKeepHttpAndNormalizedMediaWithoutOutput() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse(headers = headersOf("Content-Type", "TEXT/EVENT-STREAM; charset=utf-8"), body = completed("{}")))
            val e = failure(ResponseFailureKind.INVALID_RESULT) {
                ResponsesLlmClient({ session() }, model, endpoint = server.url("/responses").toString()).complete(request)
            }
            val d = assertDiagnostic(e, ResponseStage.SCHEMA_VALIDATION, TerminalEvent.COMPLETED)
            assertEquals(JsonPrimitive(200), d["http_status"]); assertEquals("text/event-stream", d.string("media_type"))
            assertFalse(d.toString().contains("charset")); assertNull(d["body"])
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun diagnosticReadFailureNeverLeaksExceptionTextOrChangesRejectionStage() {
        val secret = "sensitive-io-message"
        val source = object : okio.Source {
            override fun timeout() = okio.Timeout.NONE
            override fun close() {}
            override fun read(sink: Buffer, byteCount: Long): Long = throw java.io.IOException(secret)
        }
        val body = ResponseBodyInspector.inspect(source.buffer())
        val diagnostic = ResponseDiagnostic(ResponseStage.CONTENT_TYPE, httpStatus = 200,
            media = WireMedia.parse(listOf("application/json")), bodyShape = body).toJson()
        assertEquals("content_type", diagnostic.string("stage"))
        assertEquals("interrupted", body.string("read_status")); assertEquals("unknown", body.string("classification"))
        assertFalse(diagnostic.toString().contains(secret))
    }
}
