package pro.perfectproduct.cramin.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Only exact machine codes are classified; provider prose/raw payload never leaves the parser. */
@Serializable
enum class RequestRejection {
    CONTEXT_LENGTH, OUTPUT_LIMIT, TOKEN_BUDGET, PAYLOAD_SIZE, UNSUPPORTED_PARAMETER,
    INVALID_SCHEMA, INVALID_REQUEST, MODEL_OR_ROUTE, CAPABILITIES_UNKNOWN, LOCAL_BUDGET, UNKNOWN;

    companion object {
        /** Some routes wrap native JSON in metadata.raw. Read machine fields only, never prose. */
        fun nativeError(error: JsonObject?): JsonObject? {
            val raw = (error?.get("metadata") as? JsonObject)?.get("raw")
            val root = when (raw) {
                is JsonObject -> raw
                is JsonPrimitive -> raw.contentOrNull?.takeIf { it.length <= 32768 }?.let {
                    runCatching { LlmJson.lenient.parseToJsonElement(it) as? JsonObject }.getOrNull()
                }
                else -> null
            }
            return root?.get("error") as? JsonObject ?: root
        }
        fun classify(error: JsonObject?, httpStatus: Int): RequestRejection {
            val metadata = error?.get("metadata") as? JsonObject
            fun code(value: JsonElement?): String? = (value as? JsonPrimitive)?.contentOrNull
            val native = nativeError(error)
            val codes = listOf(code(metadata?.get("error_type")), code(metadata?.get("provider_code")), code(error?.get("code")), code(native?.get("code")), code(native?.get("type")))
            for (value in codes) when (value) {
                "context_length_exceeded" -> return CONTEXT_LENGTH
                "max_tokens_exceeded" -> return OUTPUT_LIMIT
                "token_limit_exceeded" -> return TOKEN_BUDGET
                "payload_too_large", "string_too_long" -> return PAYLOAD_SIZE
                "unsupported_parameter", "unsupported_value" -> return UNSUPPORTED_PARAMETER
                "invalid_json_schema", "invalid_schema" -> return INVALID_SCHEMA
                "model_not_found", "not_found", "no_available_provider" -> return MODEL_OR_ROUTE
            }
            if ("invalid_request" in codes || "invalid_prompt" in codes || "unprocessable" in codes) return INVALID_REQUEST
            return when (httpStatus) { 404 -> MODEL_OR_ROUTE; 413 -> PAYLOAD_SIZE; else -> UNKNOWN }
        }
    }
}
