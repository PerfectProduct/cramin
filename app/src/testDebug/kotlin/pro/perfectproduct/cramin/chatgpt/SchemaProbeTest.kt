package pro.perfectproduct.cramin.chatgpt

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pro.perfectproduct.cramin.llm.Schemas
import java.io.File

/** All credentials/models/responses are synthetic; HTTP is loopback-only. No device account used. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SchemaProbeTest {
    @get:Rule val temp = TemporaryFolder()
    private val catalogJson = """{"models":[{"slug":"hidden","display_name":"Hidden","visibility":"hide"},{"slug":"synthetic-b","display_name":"B","visibility":"list"},{"slug":"synthetic-a","display_name":"A","visibility":"list"}]}"""
    private val models = AccountModel.fromCatalog(Json.parseToJsonElement(catalogJson).jsonObject)
    private val secret = "synthetic-secret-never-display"
    private fun session(expires: Long = System.currentTimeMillis() / 1000 + 3600) = AccessSession(secret, REQUESTED_SCOPES, expires)
    private val valid = """{"seg":[{"from":0,"to":0,"t":"Вода замерзает при нуле градусов Цельсия.","sourceIds":["synthetic-id"]}]}"""
    private fun terminal(text: String = valid, status: String = "completed", usage: JsonElement = buildJsonObject {
        put("input_tokens", 30); put("output_tokens", 25); put("total_tokens", 55)
        putJsonObject("output_tokens_details") { put("reasoning_tokens", 4) }
        put("untrusted", secret)
    }) = "data: " + buildJsonObject {
        put("type", "response.completed")
        putJsonObject("response") {
            put("status", status); put("model", models.first().slug); put("usage", usage)
            putJsonArray("output") { addJsonObject {
                put("type", "message"); put("role", "assistant"); put("status", "completed")
                putJsonArray("content") { addJsonObject { put("type", "output_text"); put("text", text) } }
            } }
        }
    } + "\n\n"
    private fun probe(dir: File, server: MockWebServer? = null, credential: () -> AccessSession = { session() },
                      fetch: suspend (AccessSession) -> List<AccountModel> = { models }): SchemaProbe =
        SchemaProbe(ProbeHistoryStore(dir), credential, fetch) { s, m ->
            check(server != null) { "inference_must_not_run" }
            ResponsesLlmClient({ s }, m, endpoint = server.url("/v1/responses").toString())
        }
    private suspend fun ready(probe: SchemaProbe) {
        probe.loadCatalog(); probe.select(models.first().slug)
        assertTrue(probe.state.value.canSend)
    }

    private fun sse(body: String = terminal()) = MockResponse(
        headers = headersOf("Content-Type", "Text/Event-Stream; charset=utf-8"), body = body)

    @Test fun manualSuccessCanBeRepeatedAndBothResultsSurviveRestart() = runBlocking {
        val dir = temp.newFolder()
        MockWebServer().use { server ->
            server.start(); server.enqueue(sse()); server.enqueue(sse())
            val p = probe(dir, server); ready(p); p.runManual()
            assertTrue(p.state.value.canSend); assertEquals(1, server.requestCount)
            val first = p.state.value.report!!
            assertEquals("response.completed", first.string("event"))
            assertEquals("completed", first.string("terminal_status"))
            assertEquals(JsonPrimitive(true), first["schema_valid"])
            assertEquals(Json.parseToJsonElement(valid), first["result"])
            assertEquals(JsonPrimitive(55), first["usage"]!!.jsonObject["total_tokens"])
            assertNotNull(first.string("started_at")); assertNotNull(first.string("finished_at"))
            assertFalse(first.toString().contains(secret))
            val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
            assertChatGptPlanHttpBody(body)
            assertNull(SchemaProbe.request(models.first()).maxTokens)
            assertNull(SchemaProbe.request(models.first()).temperature)
            assertTrue(body["input"].toString().contains(SchemaProbe.SENTENCE))
            assertEquals(Schemas.TRANSLATE, body["text"]!!.jsonObject["format"]!!.jsonObject["schema"])
            assertEquals(JsonPrimitive(true), body["text"]!!.jsonObject["format"]!!.jsonObject["strict"])
            p.runManual()
            assertEquals(2, server.requestCount); assertTrue(p.state.value.canSend)
            assertEquals(2, p.state.value.history.size)
            assertNotEquals(first.string("attempt_id"), p.state.value.report!!.string("attempt_id"))
            val restarted = probe(dir, server)
            assertEquals(p.state.value.history, restarted.state.value.history)
            ready(restarted) // Catalog and selection still do not POST.
            assertEquals(2, server.requestCount)
            assertEquals(first, restarted.state.value.history.first())
        }
    }

    @Test fun eachFailureAllowsAnotherExplicitPressWithoutAutomaticRetry() = runBlocking {
        val failures = listOf(
            MockResponse(code = 503, headers = headersOf("Retry-After", "0"), body = secret),
            MockResponse(code = 401, body = secret),
            MockResponse(headers = headersOf("Content-Type", "text/html"), body = "<html>$secret</html>"),
            sse("data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n"),
            sse(terminal("{}")),
            sse(terminal(status = "in_progress")),
            sse("data: {\"type\":\"response.failed\",\"response\":{\"status\":\"failed\",\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}}\n\n"),
        )
        for (failure in failures) MockWebServer().use { server ->
            server.start(); server.enqueue(failure); server.enqueue(sse())
            val p = probe(temp.newFolder(), server); ready(p); p.runManual()
            val rejected = p.state.value.report!!
            assertEquals(JsonPrimitive(false), rejected["schema_valid"])
            assertTrue(p.state.value.canSend); assertFalse(p.state.value.busy)
            assertEquals(1, server.requestCount) // No retry even with Retry-After: 0.
            assertFalse(rejected.toString().contains(secret))
            p.runManual()
            assertEquals(2, server.requestCount); assertTrue(p.state.value.canSend)
            assertEquals(listOf(rejected, p.state.value.report), p.state.value.history)
            assertEquals("completed", p.state.value.report!!.string("status"))
        }
    }

    @Test fun doublePressAndSecondControllerCannotOverlapManualRequest() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/event-stream").body(terminal())
                .bodyDelay(400, java.util.concurrent.TimeUnit.MILLISECONDS).build())
            val dir = temp.newFolder(); val p = probe(dir, server); ready(p)
            val task = async(start = CoroutineStart.UNDISPATCHED) { p.runManual() }
            assertTrue(p.state.value.busy); assertFalse(p.state.value.canSend)
            assertEquals("in_progress", p.state.value.report!!.string("status"))
            p.runManual(); p.loadCatalog(); p.select(models.last().slug)
            assertEquals(models.first(), p.state.value.selected)
            val second = probe(dir, server); ready(second); second.runManual()
            assertEquals("in_progress", second.state.value.report!!.string("status"))
            task.await()
            assertEquals(1, server.requestCount); assertEquals(1, p.state.value.history.size)
            assertTrue(p.state.value.canSend)
        }
    }

    @Test fun restartRecoversPendingAsUnknownAndOnlyManualPressSendsAgain() = runBlocking {
        val dir = temp.newFolder()
        val before = ProbeHistoryStore(dir, processId = "synthetic-old-process", now = { "2026-10-10T09:00:00Z" })
        val id = before.start(models.last().slug)!! // Simulated durable record before process death.
        val after = ProbeHistoryStore(dir, processId = "synthetic-new-process", now = { "2026-10-10T09:01:00Z" })
        MockWebServer().use { server ->
            server.start(); server.enqueue(sse())
            val p = SchemaProbe(after, { session() }, { models }) { s, m ->
                ResponsesLlmClient({ s }, m, endpoint = server.url("/v1/responses").toString())
            }
            val unknown = p.state.value.report!!
            assertEquals(id, unknown.string("attempt_id")); assertEquals("unknown", unknown.string("status"))
            assertEquals(models.last().slug, unknown.string("model"))
            assertEquals("2026-10-10T09:00:00Z", unknown.string("started_at"))
            assertEquals(JsonNull, unknown["finished_at"]); assertEquals(JsonNull, unknown["usage"])
            assertEquals(JsonNull, unknown["terminal_status"])
            assertEquals("unknown", unknown.string("terminal_observation"))
            assertEquals(JsonPrimitive(false), unknown["schema_valid"])
            ready(p); assertEquals(0, server.requestCount)
            assertEquals(listOf(unknown), after.history())
            p.runManual(); assertEquals(1, server.requestCount)
            assertEquals(unknown, p.state.value.history.first()); assertEquals(2, p.state.value.history.size)
        }
    }

    @Test fun cancellationKeepsUnknownHistoryAndAllowsLaterManualRequest() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/event-stream").body(terminal())
                .bodyDelay(400, java.util.concurrent.TimeUnit.MILLISECONDS).build())
            val dir = temp.newFolder(); val p = probe(dir, server); ready(p)
            val task = async(start = CoroutineStart.UNDISPATCHED) { p.runManual() }
            withContext(Dispatchers.IO) { server.takeRequest() }
            task.cancelAndJoin()
            assertEquals("unknown", p.state.value.report!!.string("status"))
            assertTrue(p.state.value.canSend); assertFalse(p.state.value.busy)
            assertEquals(1, server.requestCount)
            assertEquals(p.state.value.history, probe(dir).state.value.history)
        }
    }

    @Test fun legacyMarkerAndReportRemainByteIdenticalAndDoNotBlockNewRequests() = runBlocking {
        val dir = temp.newFolder()
        val marker = File(dir, "chatgpt-schema-probe-v1.used").apply { writeText("used-before-diagnostics") }
        val old = """{"model":"gpt-6.1-sol","status":"invalid_result","schema_valid":false,"message":"HTTP 200; завершение не подтверждено.","usage":null}"""
        val oldFile = File(dir, "chatgpt-schema-probe-v1.json").apply { writeText(old) }
        val markerBytes = marker.readBytes(); val reportBytes = oldFile.readBytes()
        MockWebServer().use { server ->
            server.start(); server.enqueue(sse())
            val p = probe(dir, server)
            assertEquals("legacy_one_shot", p.state.value.report!!.string("source"))
            assertNull(p.state.value.report!!["diagnostic"])
            ready(p); assertEquals(0, server.requestCount); p.runManual()
            p.invalidateCatalog(); ready(p)
            assertEquals(1, server.requestCount); assertTrue(p.state.value.canSend)
            assertArrayEquals(markerBytes, marker.readBytes()); assertArrayEquals(reportBytes, oldFile.readBytes())
            assertEquals(2, p.state.value.history.size)
            assertEquals(p.state.value.history, probe(dir).state.value.history)
        }
    }

    @Test fun legacyMarkerWithMissingOrTornReportIsUnknownHistoryNotSendRestriction() = runBlocking {
        for (body in listOf<String?>(null, "broken")) {
            val dir = temp.newFolder()
            val marker = File(dir, "chatgpt-schema-probe-v1.used").apply { writeText("old-used") }
            body?.let { File(dir, "chatgpt-schema-probe-v1.json").writeText(it) }
            val p = probe(dir); ready(p)
            assertTrue(p.state.value.canSend); assertEquals("unknown", p.state.value.report!!.string("status"))
            assertEquals("old-used", marker.readText())
            body?.let { assertEquals(it, File(dir, "chatgpt-schema-probe-v1.json").readText()) }
        }
    }

    @Test fun historyPreservesSelectedSlugsAcrossManualFailures() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse(code = 503)); server.enqueue(MockResponse(code = 503))
            val p = probe(temp.newFolder(), server); ready(p); p.runManual()
            p.select(models.last().slug); p.runManual()
            assertEquals(models.map { it.slug }, p.state.value.history.map { it.string("model") })
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun diagnosticHistoryAndExportDistinguishMissingAndRejectedTerminalWithoutSecrets() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(headers = headersOf("Content-Type", "Text/HTML; x-token=$secret", "Set-Cookie", secret),
                body = "<html><body>$secret</body></html>"))
            server.enqueue(sse(terminal(status = "in_progress")))
            server.enqueue(sse())
            val dir = temp.newFolder(); val p = probe(dir, server); ready(p)
            repeat(3) { p.runManual() }
            val history = p.state.value.history
            val absent = history[0]["diagnostic"]!!.jsonObject
            assertEquals("content_type", absent.string("stage")); assertEquals("text/html", absent.string("media_type"))
            assertEquals("html", absent["body"]!!.jsonObject.string("classification"))
            assertEquals(JsonPrimitive(200), absent["http_status"])
            assertEquals("not_observed", history[0].string("terminal_observation"))
            assertEquals(JsonNull, history[0]["terminal_status"])
            assertEquals("received_rejected", history[1].string("terminal_observation"))
            assertEquals("in_progress", history[1].string("terminal_status"))
            assertEquals("received_validated", history[2].string("terminal_observation"))
            assertEquals("completed", history[2].string("terminal_status"))
            val exported = probeHistoryReport(history)
            assertEquals(JsonArray(history), Json.parseToJsonElement(exported).jsonObject["attempts"])
            for (sensitive in listOf(secret, "<html>", "Set-Cookie", "Authorization", "owner_process"))
                assertFalse(exported.contains(sensitive))
            assertEquals(history, probe(dir).state.value.history)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun missingUsageRemainsNullInHistoryAfterSuccess() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(sse(terminal(usage = JsonNull)))
            val dir = temp.newFolder(); val p = probe(dir, server); ready(p); p.runManual()
            assertTrue(p.state.value.canSend); assertEquals(JsonNull, p.state.value.report!!["usage"])
            assertEquals(JsonNull, probe(dir).state.value.report!!["usage"])
        }
    }

    @Test fun expiredOAuthBeforeNextManualSendDoesNotRecordOrSendAttempt() = runBlocking {
        var expires = System.currentTimeMillis() / 1000 + 3600
        val p = probe(temp.newFolder(), credential = { session(expires) }); ready(p)
        expires = 1; p.runManual()
        assertTrue(p.state.value.history.isEmpty()); assertFalse(p.state.value.canSend)
        assertTrue(p.state.value.catalogStatus.contains("Повторите вход"))
    }

    @Test fun historyWriteFailurePreventsSendingWithoutErasingLegacyFiles() = runBlocking {
        val dir = temp.newFolder()
        val marker = File(dir, "chatgpt-schema-probe-v1.used").apply { writeText("old-used") }
        val p = probe(dir); ready(p)
        File(dir, "chatgpt-schema-probe-history-v1.json.new").mkdir()
        p.runManual()
        assertFalse(p.state.value.available); assertFalse(p.state.value.canSend)
        assertEquals("old-used", marker.readText())
    }

    @Test fun corruptHistoryDisablesSendingAndIsNotReset() {
        val dir = temp.newFolder()
        val file = File(dir, "chatgpt-schema-probe-history-v1.json").apply { writeText("broken") }
        val p = probe(dir)
        assertFalse(p.state.value.available); assertFalse(p.state.value.canSend)
        assertEquals("broken", file.readText())
    }

    @Test fun missingContentTypeRoutePersistsForSuccessAndFailureWithoutRewritingHistory() = runBlocking {
        val dir = temp.newFolder()
        val store = ProbeHistoryStore(dir)
        val oldId = store.start(models.first().slug)!!
        store.finish(oldId, buildJsonObject {
            put("model", models.first().slug); put("status", "invalid_result"); put("schema_valid", false)
            put("usage", JsonNull)
            putJsonObject("diagnostic") {
                put("stage", "content_type"); put("http_status", 200); put("media_type_state", "missing")
                putJsonObject("body") { put("classification", "sse_like"); put("read_status", "prefix_limit") }
            }
        })
        val oldRecord = store.history().single()
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = terminal()))
            server.enqueue(MockResponse(body = terminal("{}")))
            server.enqueue(MockResponse(body = terminal()))
            val p = probe(dir, server); ready(p)
            repeat(3) { index ->
                p.runManual(); assertTrue(p.state.value.canSend)
                val report = p.state.value.report!!
                val d = report["diagnostic"]!!.jsonObject
                assertEquals("missing", d.string("media_type_state")); assertEquals(JsonNull, d["media_type"])
                assertEquals(JsonPrimitive(true), d["sse_parser_without_content_type"])
                assertEquals(JsonPrimitive(200), d["http_status"])
                assertNull(d["body"])
                if (index == 1) {
                    assertEquals("schema_validation", d.string("stage"))
                    assertEquals("received_rejected", report.string("terminal_observation"))
                    assertEquals(JsonPrimitive(false), report["schema_valid"])
                } else {
                    assertEquals("response_completed", d.string("stage"))
                    assertEquals("received_validated", report.string("terminal_observation"))
                    assertEquals(JsonPrimitive(true), report["schema_valid"])
                    assertEquals(JsonPrimitive(55), report["usage"]!!.jsonObject["total_tokens"])
                }
            }
            assertEquals(3, server.requestCount); assertEquals(4, p.state.value.history.size)
            assertEquals(oldRecord, p.state.value.history.first())
            val restarted = probe(dir, server)
            assertEquals(p.state.value.history, restarted.state.value.history)
            val exported = Json.parseToJsonElement(probeHistoryReport(restarted.state.value.history)).jsonObject
            assertEquals(JsonArray(p.state.value.history), exported["attempts"])
            assertFalse(exported.toString().contains(secret))
            assertEquals(oldRecord, store.history().first())
        }
    }

    @Test fun newStoreCannotClaimWhileSameProcessAttemptIsActiveButCanAfterFinish() {
        val dir = temp.newFolder(); val one = ProbeHistoryStore(dir); val two = ProbeHistoryStore(dir)
        val id = one.start(models.first().slug)!!
        assertNull(two.start(models.last().slug))
        one.finish(id, buildJsonObject { put("status", "unknown"); put("usage", JsonNull) })
        assertNotNull(two.start(models.last().slug))
        assertEquals(2, two.history().size)
    }
}
