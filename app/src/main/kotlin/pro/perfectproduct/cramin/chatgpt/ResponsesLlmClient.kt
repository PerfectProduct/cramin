package pro.perfectproduct.cramin.chatgpt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import okio.BufferedSink
import pro.perfectproduct.cramin.llm.*
import pro.perfectproduct.cramin.util.useCancellable
import java.io.IOException
import java.io.EOFException

/** Secrets deliberately have no generated toString(). Provide only validated, unexpired OAuth credentials. */
internal class AccessSession(val accessToken: String, val grantedScopes: Set<String>, val expiresAt: Long) {
    fun requireUsable() {
        check(grantedScopes.containsAll(REQUESTED_SCOPES)) { "plan_permission_missing" }
        check(expiresAt > System.currentTimeMillis() / 1000 + 30) { "access_token_expired" }
        check(accessToken.isNotBlank()) { "access_token_missing" }
    }
    companion object {
        fun fromRecord(record: JsonObject): AccessSession {
            val tokens = record["tokens"] as? JsonObject ?: error("access_token_missing")
            check(record["plan_enabled"] == JsonPrimitive(true)) { "plan_permission_missing" }
            return AccessSession(tokens.string("access_token") ?: error("access_token_missing"),
                ScopeGrant(tokens.string("scope") ?: "").scopes,
                record["expires_at"]?.jsonPrimitive?.longOrNull ?: 0).also { it.requireUsable() }
        }
    }
}

/** Must come from this account's public model catalog; no OpenRouter model mapping or hardcoded default. */
class AccountModel private constructor(val slug: String, val displayName: String) {
    companion object {
        fun fromCatalog(root: JsonObject): List<AccountModel> = root.getValue("models").jsonArray.map { it.jsonObject }
            .filter { it.string("visibility") == "list" }
            .map { AccountModel(it.string("slug")?.takeIf { s -> s.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) } ?: error("model_slug"),
                it.string("display_name")?.takeIf { s -> s.isNotBlank() && s.length <= 160 && s.none(Char::isISOControl) }
                    ?: error("model_name")) }
            .also { require(it.map(AccountModel::slug).distinct().size == it.size) { "duplicate_model_slug" } }
    }
}

internal enum class ResponseFailureKind { HTTP, REFUSAL, FAILED, INCOMPLETE, INTERRUPTED, INVALID_RESULT, USAGE_LIMIT, USAGE_UNAVAILABLE }
internal class ResponseFailure(
    val kind: ResponseFailureKind,
    val code: String? = null,
    val httpStatus: Int? = null,
    val requestId: String? = null,
    val bodyKeys: Set<String> = emptySet(),
    val diagnostic: ResponseDiagnostic? = null,
) : Exception("Responses: $kind")

/** Public Responses transport; no automatic inference retries or provider fallback. */
internal class ResponsesLlmClient(
    private val sessionProvider: suspend () -> AccessSession,
    private val selectedModel: AccountModel,
    private val http: OkHttpClient = prototypeHttp().newBuilder()
        .readTimeout(300, java.util.concurrent.TimeUnit.SECONDS).callTimeout(360, java.util.concurrent.TimeUnit.SECONDS).build(),
    private val endpoint: String = "$RESOURCE/responses",
    private val syntheticDiagnostics: ((JsonObject) -> Unit)? = null,
) : LlmClient {
    /** Only terminal metadata with numeric usage fields; never raw headers, errors or credentials. */
    var completionEvidence: JsonObject? = null
        private set
    override suspend fun complete(request: LlmRequest): LlmResponse = withContext(Dispatchers.IO) {
        completionEvidence = null
        val session = sessionProvider().also { it.requireUsable() }
        val body = buildBody(request, selectedModel)
        syntheticDiagnostics?.invoke(buildJsonObject { put("kind", "request"); put("body", body) })
        val jsonBody = body.toString().toRequestBody("application/json".toMediaType())
        // OkHttp may follow 503 Retry-After: 0 even with retryOnConnectionFailure(false).
        // A one-shot body prevents every automatic re-send after an HTTP response.
        val oneShot = object : RequestBody() {
            override fun contentType() = jsonBody.contentType()
            override fun contentLength() = jsonBody.contentLength()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) = jsonBody.writeTo(sink)
        }
        val req = Request.Builder().url(endpoint).header("Authorization", "Bearer ${session.accessToken}")
            .header("Accept", "text/event-stream")
            .post(oneShot).build()
        var streamDiagnostic: ResponseDiagnostic? = null
        try {
            http.newCall(req).useCancellable { response ->
                val id = response.header("x-request-id") ?: response.header("openai-request-id")
                val media = WireMedia.parse(response.headers.values("Content-Type"))
                if (!response.isSuccessful) {
                    val shape = ResponseBodyInspector.inspect(response.body.source())
                    val keys = (shape["root_keys"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()
                    throw failure(shape.string("known_error_code"), ResponseFailureKind.HTTP, response.code, id, keys,
                        ResponseDiagnostic(ResponseStage.HTTP, httpStatus = response.code, media = media, bodyShape = shape))
                }
                val withoutContentType = media.state == "missing"
                if (!media.isSse && !withoutContentType) throw ResponseFailure(ResponseFailureKind.INVALID_RESULT, httpStatus = response.code, requestId = id,
                    diagnostic = ResponseDiagnostic(ResponseStage.CONTENT_TYPE, httpStatus = response.code, media = media,
                        bodyShape = ResponseBodyInspector.inspect(response.body.source())))
                streamDiagnostic = ResponseDiagnostic(ResponseStage.TRANSPORT, httpStatus = response.code,
                    media = media, sseParserWithoutContentType = withoutContentType)
                try {
                    // Missing header is only a parser-selection decision, never evidence of SSE validity.
                    // Pass the ORIGINAL source: the consuming body inspector must not run on this path.
                    val result = consume(response.body.source(), request, id, syntheticDiagnostics) { evidence ->
                        completionEvidence = JsonObject(evidence + ("diagnostic" to ResponseDiagnostic(
                            ResponseStage.RESPONSE_COMPLETED, TerminalEvent.COMPLETED, response.code, media,
                            terminalStatus = "completed", sseParserWithoutContentType = withoutContentType,
                            usage = evidence["usage"] ?: JsonNull).toJson()))
                    }
                    result.copy(terminalMetadata = completionEvidence)
                } catch (e: ResponseFailure) {
                    throw ResponseFailure(e.kind, e.code, response.code, e.requestId, e.bodyKeys,
                        (e.diagnostic ?: ResponseDiagnostic(ResponseStage.TRANSPORT)).copy(httpStatus = response.code,
                            media = media, sseParserWithoutContentType = withoutContentType))
                }
            }
        } catch (_: IOException) {
            throw ResponseFailure(ResponseFailureKind.INTERRUPTED, httpStatus = streamDiagnostic?.httpStatus,
                diagnostic = streamDiagnostic ?: ResponseDiagnostic(ResponseStage.TRANSPORT))
        }
    }

    companion object {
        fun buildBody(request: LlmRequest, selected: AccountModel): JsonObject {
            require(request.model == selected.slug) { "explicit_account_model_required" }
            return buildBody(request)
        }

        internal fun buildBody(request: LlmRequest): JsonObject {
            require(request.role.isText && request.model.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")))
            PrototypeSchema.checkSchema(request.schema)
            return buildJsonObject {
                put("model", request.model); put("instructions", request.system)
                putJsonArray("input") { addJsonObject { put("role", "user"); put("content", request.user) } }
                put("store", false); put("stream", true)
                // ChatGPT plan OAuth forbids max_output_tokens and temperature, even when
                // present in the shared LlmRequest. Serialize only this route's supported fields.
                putJsonObject("text") { putJsonObject("format") {
                    put("type", "json_schema"); put("name", request.schemaName); put("strict", true); put("schema", request.schema)
                } }
            }
        }

        suspend fun listModels(session: AccessSession, http: OkHttpClient = prototypeHttp(), endpoint: String = "$RESOURCE/models"): List<AccountModel> = withContext(Dispatchers.IO) {
            session.requireUsable()
            http.newCall(Request.Builder().url(endpoint)
                .header("Authorization", "Bearer ${session.accessToken}").build()).useCancellable {
                if (!it.isSuccessful) throw ResponseFailure(ResponseFailureKind.HTTP, httpStatus = it.code)
                val raw = it.body.source().readUtf8Bounded(1_048_576)
                AccountModel.fromCatalog(Json.parseToJsonElement(raw).jsonObject)
            }
        }

        private fun failure(code: String?, default: ResponseFailureKind, status: Int?, id: String?, keys: Set<String> = emptySet(),
                            diagnostic: ResponseDiagnostic? = null) =
            ResponseFailure(when (code) {
                "subscription_sharing_usage_limit_exceeded", "rate_limit_exceeded" -> ResponseFailureKind.USAGE_LIMIT
                "subscription_sharing_usage_unavailable" -> ResponseFailureKind.USAGE_UNAVAILABLE
                else -> default
            }, code, status, id, keys, diagnostic)

        private fun reject(stage: ResponseStage, id: String?, kind: ResponseFailureKind = ResponseFailureKind.INVALID_RESULT,
                           terminal: TerminalEvent? = null, status: String? = null) =
            ResponseFailure(kind, requestId = id, diagnostic = ResponseDiagnostic(stage, terminal, terminalStatus = status))

        /** SSE frames, including comments and multi-line data; EOF/[DONE] alone can never mean success. */
        fun consume(source: BufferedSource, request: LlmRequest, requestId: String? = null,
                    syntheticDiagnostics: ((JsonObject) -> Unit)? = null,
                    onCompleted: (JsonObject) -> Unit = {}): LlmResponse {
            var data = StringBuilder()
            var eventName: String? = null
            var total = 0L
            val streamOutput = CompletedStreamOutput()
            try {
                while (!source.exhausted()) {
                    val line = try { source.readUtf8LineStrict(4_194_304) } catch (_: EOFException) {
                        // A line/frame without its terminating boundary is not a received terminal event.
                        throw reject(ResponseStage.SSE_FRAMING, requestId, ResponseFailureKind.INTERRUPTED)
                    }
                    total += line.length
                    if (total > 8_388_608) throw reject(ResponseStage.SSE_FRAMING, requestId)
                    if (line.isEmpty()) {
                        if (data.isNotEmpty()) {
                            val payload = data.toString().trimEnd('\n')
                            if (payload == "[DONE]") throw reject(ResponseStage.NO_TERMINAL_EVENT, requestId, ResponseFailureKind.INTERRUPTED)
                            val event = try { parseWireJson(payload).jsonObject } catch (_: Exception) {
                                throw reject(ResponseStage.SSE_FRAMING, requestId)
                            }
                            val type = event.string("type")
                            syntheticDiagnostics?.invoke(buildJsonObject {
                                put("kind", "event"); put("type", type)
                                if (type == "response.output_text.delta") put("delta", event["delta"] ?: JsonNull)
                                if (type == "response.output_text.done") put("text", event["text"] ?: JsonNull)
                                for (key in listOf("output_index", "content_index", "item_id")) event[key]?.let { put(key, it) }
                                (event["item"] as? JsonObject)?.takeIf { it.string("type") == "message" }?.let { put("item", it) }
                                val r = event["response"] as? JsonObject
                                if (r != null && type in setOf("response.completed", "response.failed", "response.incomplete")) {
                                    for (key in listOf("model", "status", "output")) r[key]?.let { put(key, it) }
                                    put("usage", safeUsage(r["usage"] as? JsonObject))
                                }
                            })
                            val terminal = TerminalEvent.from(type)
                            if (type == null || (eventName != null && eventName != type))
                                throw reject(ResponseStage.SSE_FRAMING, requestId, terminal = terminal)
                            streamOutput.observe(event)
                            when (type) {
                                "response.refusal.delta", "response.refusal.done" -> throw reject(ResponseStage.REFUSAL, requestId, ResponseFailureKind.REFUSAL)
                                "response.failed", "error" -> {
                                    val response = event["response"] as? JsonObject
                                    val err = (response?.get("error") as? JsonObject) ?: (event["error"] as? JsonObject) ?: event
                                    throw failure(err.string("code"), ResponseFailureKind.FAILED, null, requestId,
                                        diagnostic = ResponseDiagnostic(ResponseStage.RESPONSE_FAILED, terminal,
                                            terminalStatus = response?.string("status"), errorCode = err.string("code"),
                                            usage = safeUsage(response?.get("usage") as? JsonObject)))
                                }
                                "response.incomplete" -> throw ResponseFailure(ResponseFailureKind.INCOMPLETE,
                                    requestId = requestId, diagnostic = ResponseDiagnostic(ResponseStage.RESPONSE_INCOMPLETE, terminal,
                                        terminalStatus = (event["response"] as? JsonObject)?.string("status"),
                                        usage = safeUsage((event["response"] as? JsonObject)?.get("usage") as? JsonObject)))
                                "response.completed" -> {
                                    val result = try { completed(event, request, requestId, syntheticDiagnostics, streamOutput) }
                                    catch (e: ResponseFailure) {
                                        throw ResponseFailure(e.kind, e.code, e.httpStatus, e.requestId, e.bodyKeys,
                                            e.diagnostic?.copy(usage = safeUsage((event["response"] as? JsonObject)?.get("usage") as? JsonObject)))
                                    }
                                    onCompleted(buildJsonObject {
                                        put("event", "response.completed"); put("status", "completed"); put("model", result.model)
                                        put("terminal_observation", "received_validated")
                                        put("usage", safeUsage(event.getValue("response").jsonObject["usage"] as? JsonObject))
                                    })
                                    return result
                                }
                            }
                        }
                        data = StringBuilder(); eventName = null
                    } else if (line.startsWith("data:")) {
                        data.append(line.substring(5).removePrefix(" ")).append('\n')
                        if (data.length > 4_194_304) throw reject(ResponseStage.SSE_FRAMING, requestId)
                    } else if (line.startsWith("event:")) eventName = line.substring(6).trim()
                }
            } catch (_: IOException) {
                throw reject(ResponseStage.TRANSPORT, requestId, ResponseFailureKind.INTERRUPTED)
            }
            if (data.isNotEmpty() || eventName != null) throw reject(ResponseStage.SSE_FRAMING, requestId, ResponseFailureKind.INTERRUPTED)
            throw reject(ResponseStage.NO_TERMINAL_EVENT, requestId, ResponseFailureKind.INTERRUPTED)
        }

        internal fun safeUsage(usage: JsonObject?): JsonElement {
            if (usage == null) return JsonNull
            fun number(value: JsonElement?): Long? = (value as? JsonPrimitive)
                ?.takeUnless { it.isString }?.longOrNull?.takeIf { it >= 0 }
            return buildJsonObject {
                for (key in listOf("input_tokens", "output_tokens", "total_tokens"))
                    number(usage[key])?.let { put(key, it) }
                for ((group, key) in listOf("input_tokens_details" to "cached_tokens", "output_tokens_details" to "reasoning_tokens"))
                    number((usage[group] as? JsonObject)?.get(key))?.let { n -> putJsonObject(group) { put(key, n) } }
            }
        }

        private fun completed(event: JsonObject, request: LlmRequest, id: String?, syntheticDiagnostics: ((JsonObject) -> Unit)?, streamOutput: CompletedStreamOutput): LlmResponse {
            fun invalid(stage: ResponseStage, status: String? = null) =
                reject(stage, id, terminal = TerminalEvent.COMPLETED, status = status)
            val response = event["response"] as? JsonObject ?: throw invalid(ResponseStage.TERMINAL_STATUS)
            val status = response.string("status")
            if (status != "completed" || (response["error"] != null && response["error"] != JsonNull))
                throw invalid(ResponseStage.TERMINAL_STATUS, status)
            if (response.string("model") != request.model) throw invalid(ResponseStage.MODEL_MISMATCH, status)
            val text = try {
                buildString {
                    for (item in streamOutput.completedItems(response.getValue("output").jsonArray)) {
                        val obj = item.jsonObject
                        if (obj.string("type") == "reasoning") continue
                        require(obj.string("type") == "message" && obj.string("role") == "assistant" && obj.string("status") == "completed")
                        for (part in obj.getValue("content").jsonArray) {
                            val content = part.jsonObject
                            if (content.string("type") == "refusal") throw reject(ResponseStage.REFUSAL, id,
                                ResponseFailureKind.REFUSAL, TerminalEvent.COMPLETED, status)
                            require(content.string("type") == "output_text")
                            append(content.string("text") ?: error("missing_output_text"))
                        }
                    }
                }
            } catch (e: ResponseFailure) { throw e }
            catch (_: Exception) { throw invalid(ResponseStage.OUTPUT_STRUCTURE, status) }
            syntheticDiagnostics?.invoke(buildJsonObject { put("kind", "extracted_output"); put("text", text) })
            val parsed = try { parseWireJson(text) }
                catch (_: Exception) { throw invalid(ResponseStage.JSON_PARSE, status) }
            try {
                PrototypeSchema.checkSchema(request.schema)
                PrototypeSchema.validate(parsed, request.schema)
            } catch (_: Exception) { throw invalid(ResponseStage.SCHEMA_VALIDATION, status) }
            val usage = response["usage"] as? JsonObject
            return LlmResponse(text, "stop", LlmUsage(
                (usage?.get("input_tokens") as? JsonPrimitive)?.intOrNull ?: 0,
                (usage?.get("output_tokens") as? JsonPrimitive)?.intOrNull ?: 0,
                null, // ChatGPT plan usage has no per-request USD price here. Unknown is not zero.
            ), request.model)
        }

    }
}

private fun BufferedSource.readUtf8Bounded(limit: Long): String {
    request(limit + 1)
    return readUtf8(minOf(buffer.size, limit))
}
