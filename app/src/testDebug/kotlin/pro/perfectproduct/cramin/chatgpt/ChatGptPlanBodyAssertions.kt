package pro.perfectproduct.cramin.chatgpt

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse

/** HTTP restrictions, verified against:
 * https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations
 * Applies to top-level request fields; input messages may still have role=user.
 */
internal fun assertChatGptPlanHttpBody(body: JsonObject) {
    val unsupported = setOf(
        "background", "conversation", "max_output_tokens", "max_tool_calls", "metadata",
        "moderation", "multi_agent", "prompt", "prompt_cache_retention", "safety_identifier",
        "temperature", "top_logprobs", "top_p", "truncation", "user",
        "previous_response_id", // Also explicitly disallowed over HTTP on this route.
    )
    for (field in unsupported) assertFalse("Forbidden ChatGPT plan HTTP field: $field", field in body)
    // The probe needs only these six fields: no provider-specific fields or unsupported tools.
    assertEquals(setOf("model", "instructions", "input", "text", "store", "stream"), body.keys)
}
