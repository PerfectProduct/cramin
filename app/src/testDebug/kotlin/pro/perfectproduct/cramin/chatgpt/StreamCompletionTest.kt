package pro.perfectproduct.cramin.chatgpt

import kotlinx.serialization.json.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import pro.perfectproduct.cramin.llm.*

class StreamCompletionTest {
    private val text = """{"seg":[{"from":0,"to":0,"t":"Вода замерзает.","sourceIds":["s0"]}]}"""
    private val request = LlmRequest(ModelRole.TRANSLATE, "synthetic", "translate", "synthetic",
        Schemas.TRANSLATE_NAME, Schemas.TRANSLATE, null, null)
    private fun frame(type: String, body: JsonObject) = "data: ${JsonObject(body + ("type" to JsonPrimitive(type)))}\n\n"
    private fun stream(value: String = text, completed: Boolean = true, doneText: String = value,
        status: String = "completed", model: String = "synthetic", itemDone: Boolean = true): String {
        fun item(done: Boolean) = buildJsonObject {
            put("id", "msg_test"); put("type", "message"); put("role", "assistant")
            put("status", if (done) "completed" else "in_progress")
            putJsonArray("content") { if (done) addJsonObject { put("type", "output_text"); put("text", value) } }
        }
        fun part(key: String, v: String) = buildJsonObject {
            put("output_index", 0); put("content_index", 0); put("item_id", "msg_test"); put(key, v)
        }
        var s = frame("response.output_item.added", buildJsonObject { put("output_index", 0); put("item", item(false)) })
        s += frame("response.output_text.delta", part("delta", value))
        s += frame("response.output_text.done", part("text", doneText))
        if (itemDone) s += frame("response.output_item.done", buildJsonObject { put("output_index", 0); put("item", item(true)) })
        if (completed) s += frame("response.completed", buildJsonObject { putJsonObject("response") {
            put("model", model); put("status", status); put("output", JsonArray(emptyList()))
            putJsonObject("usage") { put("input_tokens", 404); put("output_tokens", 103); put("total_tokens", 507) }
        } })
        return s
    }
    private fun parse(s: String) = ResponsesLlmClient.consume(Buffer().writeUtf8(s), request)
    private fun rejected(s: String, stage: ResponseStage? = null): ResponseFailure {
        try { parse(s); error("accepted invalid stream") } catch (e: ResponseFailure) {
            stage?.let { assertEquals(it, e.diagnostic?.stage) }; return e
        }
    }
    @Test fun emptyTerminalOutputUsesCompletedStreamItemsAndValidatesSchema() {
        val r = parse(stream())
        assertEquals(text, r.content); assertEquals(404, r.usage.promptTokens); assertNull(r.usage.costUsd)
    }
    @Test fun finalizedTextWithoutTerminalCompletionIsNeverSuccess() {
        rejected(stream(completed = false), ResponseStage.NO_TERMINAL_EVENT)
        rejected(stream(itemDone = false), ResponseStage.OUTPUT_STRUCTURE)
        rejected(stream(doneText = "different"), ResponseStage.OUTPUT_STRUCTURE)
        rejected(stream(status = "in_progress"), ResponseStage.TERMINAL_STATUS)
        rejected(stream(model = "other"), ResponseStage.MODEL_MISMATCH)
    }
    @Test fun validationFailureRetainsTerminalMetadataAndUsage() {
        for ((value,stage) in listOf("not JSON" to ResponseStage.JSON_PARSE, "{}" to ResponseStage.SCHEMA_VALIDATION)) {
            val e = rejected(stream(value), stage)
            assertEquals(TerminalEvent.COMPLETED,e.diagnostic?.terminalEvent)
            assertEquals(JsonPrimitive(404),e.diagnostic?.usage?.jsonObject?.get("input_tokens"))
        }
    }
    @Test fun failureAfterCompleteItemsDoesNotAcceptEarlierText() {
        val s = stream(completed=false)+frame("response.failed",buildJsonObject { putJsonObject("response") {
            put("status","failed"); putJsonObject("error") { put("code","subscription_sharing_usage_limit_exceeded") }
        } })
        assertEquals(ResponseFailureKind.USAGE_LIMIT,rejected(s).kind)
    }
}
