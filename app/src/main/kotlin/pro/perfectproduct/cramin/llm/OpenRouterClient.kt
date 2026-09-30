package pro.perfectproduct.cramin.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import pro.perfectproduct.cramin.util.Log
import java.io.IOException

/**
 * Клиент OpenRouter `POST /api/v1/chat/completions` (SPEC §6.3): structured outputs со strict-схемой,
 * `usage.include`, ретраи сети/429/5xx с экспоненциальной задержкой (1, 2, 4, 8 с, до 4 попыток)
 * и учётом `Retry-After`, `finish_reason` наружу. Ключ берётся у [keyProvider] на каждый вызов и
 * никогда не логируется.
 */
class OpenRouterClient(
    private val http: OkHttpClient,
    private val keyProvider: suspend () -> String?,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val maxAttempts: Int = MAX_ATTEMPTS,
    private val requireParameters: Boolean = true,
    /**
     * Параметры, которые модель поддерживает по каталогу (null — неизвестно). С `require_parameters`
     * запрос с неподдерживаемым параметром (например, temperature у моделей OpenAI серии 5)
     * получает 404 «No endpoints found», поэтому такие параметры не отправляются.
     */
    private val paramSupport: suspend (String) -> Set<String>? = { null },
) : LlmClient {

    override suspend fun complete(request: LlmRequest): LlmResponse {
        val key = keyProvider()?.takeIf { it.isNotBlank() } ?: throw LlmException.Auth(0)
        val supported = paramSupport(request.model)
        val body = buildBody(request, supported).toString()
        var lastError: LlmException? = null
        for (attempt in 1..maxAttempts) {
            val outcome = try {
                execute(key, body)
            } catch (e: IOException) {
                Log.w(TAG, "attempt $attempt: network ${e.javaClass.simpleName}")
                Attempt.Retry(null, LlmException.Network(e.javaClass.simpleName, e))
            }
            when (outcome) {
                is Attempt.Done -> return outcome.response
                is Attempt.Fail -> throw outcome.error
                is Attempt.Retry -> {
                    lastError = outcome.error
                    if (attempt < maxAttempts) {
                        val backoff = BACKOFF_SECONDS[minOf(attempt - 1, BACKOFF_SECONDS.lastIndex)] * 1000L
                        val wait = outcome.retryAfterMs?.coerceAtMost(MAX_RETRY_AFTER_MS) ?: backoff
                        Log.i(TAG, "attempt $attempt failed (${outcome.error.message}); retry in ${wait}ms")
                        sleeper(maxOf(wait, backoff))
                    }
                }
            }
        }
        throw lastError ?: LlmException.Network("exhausted retries")
    }

    private sealed interface Attempt {
        data class Done(val response: LlmResponse) : Attempt
        data class Fail(val error: LlmException) : Attempt
        data class Retry(val retryAfterMs: Long?, val error: LlmException) : Attempt
    }

    private suspend fun execute(key: String, body: String): Attempt = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $key")
            .header("HTTP-Referer", REFERER)
            .header("X-Title", TITLE)
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body.string()
            when {
                resp.isSuccessful -> parseSuccess(text)
                resp.code == 401 || resp.code == 403 -> Attempt.Fail(LlmException.Auth(resp.code))
                resp.code == 402 -> Attempt.Fail(LlmException.Payment())
                resp.code == 429 -> Attempt.Retry(retryAfterMs(resp.header("Retry-After")), LlmException.RateLimited())
                resp.code >= 500 -> Attempt.Retry(retryAfterMs(resp.header("Retry-After")), LlmException.Server(resp.code))
                else -> Attempt.Fail(LlmException.BadRequest(resp.code, errorDetail(text)))
            }
        }
    }

    private fun parseSuccess(text: String): Attempt {
        val root = try {
            LlmJson.lenient.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            return Attempt.Fail(LlmException.InvalidResponse("not a JSON object"))
        }
        // OpenRouter может вернуть 200 с телом {"error": {...}} (например, провайдер отказал).
        root["error"]?.jsonObject?.let { err ->
            val code = err["code"]?.jsonPrimitive?.intOrNull ?: 0
            val msg = err["message"]?.jsonPrimitive?.contentOrNull ?: "provider error"
            return when (code) {
                429 -> Attempt.Retry(null, LlmException.RateLimited())
                in 500..599 -> Attempt.Retry(null, LlmException.Server(code))
                401, 403 -> Attempt.Fail(LlmException.Auth(code))
                402 -> Attempt.Fail(LlmException.Payment())
                else -> Attempt.Fail(LlmException.BadRequest(code, msg.take(200)))
            }
        }
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: return Attempt.Fail(LlmException.InvalidResponse("no choices"))
        val message = choice["message"]?.jsonObject
        val content = message?.get("content")?.let { c ->
            if (c is JsonPrimitive) c.contentOrNull else null
        } ?: return Attempt.Fail(LlmException.InvalidResponse("empty content"))
        val finish = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
            ?: choice["native_finish_reason"]?.jsonPrimitive?.contentOrNull
        val usage = root["usage"]?.jsonObject
        val llmUsage = LlmUsage(
            promptTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.intOrNull ?: 0,
            completionTokens = usage?.get("completion_tokens")?.jsonPrimitive?.intOrNull ?: 0,
            costUsd = usage?.get("cost")?.jsonPrimitive?.doubleOrNull,
        )
        val model = root["model"]?.jsonPrimitive?.contentOrNull ?: ""
        return Attempt.Done(LlmResponse(content, finish, llmUsage, model))
    }

    private fun buildBody(r: LlmRequest, supported: Set<String>?): JsonObject = buildJsonObject {
        put("model", r.model)
        put(
            "messages",
            buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", r.system) })
                add(buildJsonObject { put("role", "user"); put("content", r.user) })
            },
        )
        put(
            "response_format",
            buildJsonObject {
                put("type", "json_schema")
                put(
                    "json_schema",
                    buildJsonObject {
                        put("name", r.schemaName)
                        put("strict", true)
                        put("schema", r.schema)
                    },
                )
            },
        )
        put("usage", buildJsonObject { put("include", true) })
        if (r.temperature != null && (supported == null || "temperature" in supported)) put("temperature", r.temperature)
        r.maxTokens?.let { put("max_tokens", it) }
        if (r.reasoning != null && (supported == null || "reasoning" in supported)) put("reasoning", r.reasoning)
        if (requireParameters) {
            // Роутинг только к провайдерам, которые честно поддерживают structured outputs (CRM-DL-011).
            put("provider", buildJsonObject { put("require_parameters", true) })
        }
    }

    private fun errorDetail(text: String): String = runCatching {
        LlmJson.lenient.parseToJsonElement(text).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
    }.getOrNull()?.take(200) ?: "HTTP error"

    companion object {
        private const val TAG = "OpenRouter"
        const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"
        const val REFERER = "https://github.com/PerfectProduct/cramin"
        const val TITLE = "Cramin"
        const val MAX_ATTEMPTS = 4
        private val BACKOFF_SECONDS = longArrayOf(1, 2, 4, 8)
        private const val MAX_RETRY_AFTER_MS = 60_000L
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** `Retry-After` в секундах (HTTP-дату не разбираем: ждём по экспоненте). */
        fun retryAfterMs(header: String?): Long? = header?.trim()?.toLongOrNull()?.let { it * 1000L }

        fun isCancellation(t: Throwable): Boolean = t is CancellationException
    }
}
