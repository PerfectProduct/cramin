package pro.perfectproduct.cramin.chatgpt

import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.BufferedSource
import java.io.IOException
import java.util.Locale

/** Json's tree parser accepts bare primitive literals; reject those outside JSON's grammar. */
internal fun parseWireJson(text: String): JsonElement {
    val root = Json.parseToJsonElement(text)
    val pending = ArrayDeque<JsonElement>().apply { add(root) }
    while (pending.isNotEmpty()) {
        when (val value = pending.removeLast()) {
            is JsonObject -> pending.addAll(value.values)
            is JsonArray -> pending.addAll(value)
            is JsonPrimitive -> if (!value.isString && value != JsonNull)
                require(value.content == "true" || value.content == "false" || jsonNumber.matches(value.content))
        }
    }
    return root
}
private val jsonNumber = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

internal enum class ResponseStage {
    CONTENT_TYPE, SSE_FRAMING, TERMINAL_STATUS, MODEL_MISMATCH, JSON_PARSE, SCHEMA_VALIDATION,
    OUTPUT_STRUCTURE, NO_TERMINAL_EVENT, RESPONSE_FAILED, RESPONSE_INCOMPLETE, REFUSAL, HTTP, TRANSPORT,
    RESPONSE_COMPLETED,
}

internal enum class TerminalEvent(val wireName: String) {
    COMPLETED("response.completed"), FAILED("response.failed"), INCOMPLETE("response.incomplete");
    companion object { fun from(type: String?) = entries.firstOrNull { it.wireName == type } }
}

/** Never retains the raw Content-Type or its parameters. Unknown MIME tokens are redacted. */
internal class WireMedia private constructor(val normalized: String?, val state: String) {
    val isSse get() = normalized == "text/event-stream" && state == "parsed"
    companion object {
        private val safeTypes = setOf("text/event-stream", "application/json", "application/problem+json",
            "text/html", "text/plain", "application/octet-stream", "application/x-ndjson")
        fun parse(values: List<String>): WireMedia {
            if (values.isEmpty()) return WireMedia(null, "missing")
            if (values.size != 1) return WireMedia(null, "invalid")
            val media = values.single().trim().toMediaTypeOrNull() ?: return WireMedia(null, "invalid")
            val normalized = "${media.type}/${media.subtype}".lowercase(Locale.ROOT)
            return if (normalized in safeTypes) WireMedia(normalized, "parsed") else WireMedia("other", "unlisted")
        }
    }
}

/** Static names/booleans only. The JSON/HTML/SSE prefix used for classification is never persisted. */
internal object ResponseBodyInspector {
    const val PREFIX_LIMIT = 16_384L
    private val knownCodes = setOf("subscription_sharing_usage_limit_exceeded", "subscription_sharing_usage_unavailable",
        "subscription_sharing_unsupported_capability", "server_error", "invalid_request_error",
        "invalid_api_key", "rate_limit_exceeded")
    fun knownCode(value: String?) = value?.takeIf { it in knownCodes }
    fun knownStatus(value: String?) = value?.let {
        if (it in setOf("completed", "failed", "incomplete", "in_progress", "queued", "cancelled")) it else "unrecognized"
    }

    fun inspect(source: BufferedSource): JsonObject {
        val prefix: String
        val limited: Boolean
        try {
            source.request(PREFIX_LIMIT + 1)
            limited = source.buffer.size > PREFIX_LIMIT
            prefix = source.readUtf8(minOf(source.buffer.size, PREFIX_LIMIT))
        } catch (_: IOException) {
            return buildJsonObject { put("classification", "unknown"); put("read_status", "interrupted") }
        }
        val text = prefix.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        val sseLike = text.lineSequence().any { it.startsWith("data:") || it.startsWith("event:") }
        val htmlLike = listOf("<!doctype html", "<html", "<head", "<body").any { text.startsWith(it, ignoreCase = true) }
        val jsonLike = text.startsWith('{') || text.startsWith('[')
        val depthOk = boundedDepth(text)
        val parsed = if (!limited && depthOk && !sseLike && !htmlLike)
            runCatching { parseWireJson(text) }.getOrNull() else null
        return buildJsonObject {
            put("classification", when { parsed != null || jsonLike -> "json"; htmlLike -> "html"; sseLike -> "sse_like"; else -> "unknown" })
            put("read_status", if (limited) "prefix_limit" else "complete")
            put("prefix_limited", limited)
            if (jsonLike || parsed != null) {
                put("json_parse", when { limited -> "not_attempted_truncated"; !depthOk -> "not_attempted_depth_limit";
                    parsed == null -> "invalid"; else -> "complete" })
            }
            if (parsed != null) {
                put("json_root", when (parsed) { is JsonObject -> "object"; is JsonArray -> "array"; else -> "scalar" })
                if (parsed is JsonObject) {
                    val allowed = setOf("object", "type", "status", "response", "error", "output", "usage", "model")
                    put("root_keys", JsonArray(parsed.keys.filter { it in allowed }.sorted().map(::JsonPrimitive)))
                    val response = (parsed["response"] as? JsonObject) ?: parsed
                    put("response_object", response.string("object") == "response")
                    put("has_output_array", response["output"] is JsonArray)
                    put("has_usage_object", response["usage"] is JsonObject)
                    put("has_model_string", (response["model"] as? JsonPrimitive)?.isString == true)
                    knownStatus(response.string("status"))?.let { put("response_status", it) }
                    TerminalEvent.from(parsed.string("type"))?.let { put("json_event_type", it.wireName) }
                    val error = (response["error"] as? JsonObject) ?: (parsed["error"] as? JsonObject)
                    put("has_error_object", error != null)
                    knownCode(error?.string("code"))?.let { put("known_error_code", it) }
                }
            }
        }
    }

    // A bounded prefix must also have bounded nesting before handing it to a recursive JSON parser.
    private fun boundedDepth(text: String): Boolean {
        var depth = 0; var quoted = false; var escaped = false
        for (char in text) {
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> { depth++; if (depth > 32) return false }
                '}', ']' -> depth--
            }
        }
        return true
    }
}

internal data class ResponseDiagnostic(
    val stage: ResponseStage,
    val terminalEvent: TerminalEvent? = null,
    val httpStatus: Int? = null,
    val media: WireMedia? = null,
    val bodyShape: JsonObject? = null,
    val terminalStatus: String? = null,
    val errorCode: String? = null,
    val sseParserWithoutContentType: Boolean = false,
    val usage: JsonElement = JsonNull,
) {
    fun toJson() = buildJsonObject {
        put("stage", stage.name.lowercase(Locale.ROOT))
        put("terminal_event", terminalEvent?.wireName?.let(::JsonPrimitive) ?: JsonNull)
        put("terminal_observation", when {
            terminalEvent == null -> "not_observed"
            stage == ResponseStage.RESPONSE_COMPLETED && terminalEvent == TerminalEvent.COMPLETED -> "received_validated"
            else -> "received_rejected"
        })
        put("sse_parser_without_content_type", sseParserWithoutContentType)
        put("http_status", httpStatus?.let(::JsonPrimitive) ?: JsonNull)
        put("usage", usage)
        media?.let {
            put("media_type", it.normalized?.let(::JsonPrimitive) ?: JsonNull)
            put("media_type_state", it.state)
        }
        bodyShape?.let { put("body", it) }
        ResponseBodyInspector.knownStatus(terminalStatus)?.let { put("terminal_status", it) }
        ResponseBodyInspector.knownCode(errorCode)?.let { put("known_error_code", it) }
    }
}
