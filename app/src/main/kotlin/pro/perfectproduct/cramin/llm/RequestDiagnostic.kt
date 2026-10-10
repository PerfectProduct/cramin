package pro.perfectproduct.cramin.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import pro.perfectproduct.cramin.BuildConfig
import java.security.MessageDigest
import java.util.UUID

/** Captured from the serialized request, never from current settings or provider prose. */
@Serializable
data class RequestDiagnostic(
    val attemptId: String,
    val startedAtEpochMs: Long,
    val respondedAtEpochMs: Long? = null,
    val buildVersion: String,
    val configOrigin: ConfigOrigin,
    val requestedModel: String?,
    val responseModel: String? = null,
    val provider: String? = null,
    val httpStatus: Int? = null,
    val apiStatus: Int? = null,
    val requestId: String? = null,
    val systemChars: Int,
    val userChars: Int,
    val inputUtf8Bytes: Int,
    val serializedBytes: Int,
    val estimatedInputTokens: Int,
    val estimateMethod: String = "SERIALIZED_UTF8_BYTES_PROXY_V1_NOT_TOKENIZER",
    val maxTokens: String,
    val temperature: String,
    val reasoning: JsonObject?,
    val reasoningState: String,
    val structuredOutputs: String = "JSON_SCHEMA_STRICT",
    val schemaSha256: String,
    val requireParameters: Boolean,
    val rejection: RequestRejection? = null,
    val parameter: String? = null,
    val reportedLimits: Map<String, Long> = emptyMap(),
    val processingAttemptId: String? = null,
    val logicalRequestId: String? = null,
    val jobIndex: Int? = null,
    val partPath: String? = null,
    val observation: String = "LEGACY_UNKNOWN",
    val responseEvidence: JsonObject? = null,

) {
    fun copyText(): String = "attemptId scope: ONE_HTTP_ATTEMPT; processing correlation absent in legacy records\n" + codec.encodeToJsonElement(serializer(), this).jsonObject.entries.joinToString("\n") {
        "${it.key}: ${if (it.value == JsonNull) "UNKNOWN" else it.value}"
    }
    fun response(status: Int, root: JsonObject?, requestHeader: String?, reason: RequestRejection?): RequestDiagnostic {
        val error = errorObject(root)
        val meta = error?.get("metadata") as? JsonObject
        val native = RequestRejection.nativeError(error)
        val param = text(error?.get("param")) ?: text(meta?.get("param")) ?: text(native?.get("param"))
        val limits = listOf("limit", "max_tokens", "max_context_length", "context_length", "max_completion_tokens").mapNotNull { key ->
            ((meta?.get(key) ?: native?.get(key)) as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }?.let { key to it }
        }.toMap()
        return copy(respondedAtEpochMs = System.currentTimeMillis(), httpStatus = status,
            apiStatus = (error?.get("code") as? JsonPrimitive)?.intOrNull?.takeIf { it in 100..599 },
            requestId = safeRequestId(requestHeader) ?: safeRequestId(text(root?.get("id"))),
            responseModel = safeModel(text(root?.get("model")))?.takeIf { it == requestedModel },
            provider = (text(meta?.get("provider_name")) ?: text(root?.get("provider")))?.takeIf { it in providers },
            rejection = reason, parameter = param?.takeIf { it in parameters }, reportedLimits = limits)
    }
    companion object {
        private val codec = Json { encodeDefaults = true }
        private val providers = setOf("OpenAI", "Anthropic", "Google", "Google AI Studio", "Google Vertex", "Azure", "Amazon Bedrock", "DeepInfra", "Together", "Fireworks", "Groq", "Mistral", "Cerebras")
        private val parameters = setOf("max_tokens", "max_completion_tokens", "temperature", "reasoning", "reasoning.effort", "reasoning.max_tokens", "response_format", "response_format.json_schema", "model", "messages", "provider")
        private fun text(v: JsonElement?) = (v as? JsonPrimitive)?.contentOrNull
        private fun safeModel(v: String?) = v?.takeIf { it.length <= 160 && it.matches(Regex("[a-zA-Z0-9._:-]+/[a-zA-Z0-9._:/-]+")) }
        private fun safeRequestId(v: String?) = v?.takeIf { it.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|gen-[0-9]{8,16}-[A-Za-z0-9]{8,64}")) }
        fun errorObject(root: JsonObject?): JsonObject? = root?.get("error") as? JsonObject
            ?: (((root?.get("choices") as? JsonArray)?.firstOrNull() as? JsonObject)?.get("error") as? JsonObject)
        fun capture(request: LlmRequest, body: JsonObject): RequestDiagnostic {
            val reasoning = body["reasoning"] as? JsonObject
            val safeReasoning = reasoning?.let { r -> buildJsonObject {
                (r["effort"] as? JsonPrimitive)?.contentOrNull?.takeIf { it in setOf("none", "minimal", "low", "medium", "high", "xhigh") }?.let { put("effort", it) }
                (r["max_tokens"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }?.let { put("max_tokens", it) }
                for (key in listOf("enabled", "exclude")) (r[key] as? JsonPrimitive)?.booleanOrNull?.let { put(key, it) }
            } }
            val bytes = body.toString().toByteArray(Charsets.UTF_8).size
            return RequestDiagnostic(UUID.randomUUID().toString(), System.currentTimeMillis(),
                processingAttemptId = request.processingAttemptId,
                logicalRequestId = request.logicalRequestId, jobIndex = request.jobIndex,
                partPath = request.partPath?.takeIf { it.matches(Regex("[LR]{0,6}")) },
                buildVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", configOrigin = request.configOrigin,
                requestedModel = if (request.provider == TextProvider.CHATGPT_PLAN) text(body["model"])?.takeIf { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) }
                    else safeModel(text(body["model"])), systemChars = request.system.length, userChars = request.user.length,
                inputUtf8Bytes = (request.system + request.user).toByteArray(Charsets.UTF_8).size,
                serializedBytes = bytes, estimatedInputTokens = bytes,
                maxTokens = (body["max_tokens"] as? JsonPrimitive)?.longOrNull?.toString() ?: "NOT_SENT",
                temperature = (body["temperature"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }?.toString() ?: "NOT_SENT",
                reasoning = safeReasoning, reasoningState = when { reasoning == null -> "NOT_SENT"; safeReasoning == reasoning -> "COMPLETE"; else -> "PARTIAL_ALLOWLIST" },
                schemaSha256 = MessageDigest.getInstance("SHA-256").digest(request.schema.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) },
                requireParameters = (body["provider"] as? JsonObject)?.get("require_parameters") == JsonPrimitive(true))
        }
    }
}

@Serializable
enum class ConfigOrigin { NEW_SNAPSHOT, LEGACY_FALLBACK, UNKNOWN }
