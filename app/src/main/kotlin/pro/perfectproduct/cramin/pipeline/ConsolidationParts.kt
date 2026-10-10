package pro.perfectproduct.cramin.pipeline

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import pro.perfectproduct.cramin.llm.*

/** Durable tree inside the existing Job JSON. Splits only BETWEEN lexical groups. */
@Serializable
internal data class ConsolidationParts(
    val version: Int = 1,
    val inputHash: String,
    val split: Set<String> = emptySet(),
    val done: Map<String, String> = emptyMap(),
    val blocked: Set<String> = emptySet(),
    val active: String? = null,
) {
    fun encode(): String = buildJsonObject { put("consolidationParts", Json.encodeToJsonElement(serializer(), this@ConsolidationParts)) }.toString()
    companion object {
        fun decode(raw: String?): ConsolidationParts? {
            if (raw == null) return null
            val root = LlmJson.parse<JsonObject>(raw)
            val value = root["consolidationParts"] ?: return null
            return LlmJson.strict.decodeFromJsonElement(serializer(), value).also {
                if (it.version != 1) throw ConsolidationCacheInvalid()
            }
        }
    }
}

internal data class ConsolidationBudget(val inputBytes: Int, val output: Int, val context: Int, val knownContext: Boolean) {
    val fits: Boolean get() = inputBytes.toLong() + output + 1024 <= context
    companion object {
        fun evaluate(request: LlmRequest, model: CatalogModel?): ConsolidationBudget {
            val context = model?.contextLength?.takeIf { it > 0 } ?: 32768
            val output = request.maxTokens ?: minOf(4000, model?.maxCompletionTokens?.takeIf { it > 0 } ?: 4000)
            if (output <= 0 || output >= context || model?.maxCompletionTokens?.let { it > 0 && output > it } == true)
                throw ConsolidationLimit()
            val bytes = ChatRequestBody.forSizing(request.copy(maxTokens = output), model?.supportedParameters?.toSet(), true)
                .toString().toByteArray(Charsets.UTF_8).size
            return ConsolidationBudget(bytes, output, context, model?.contextLength?.let { it > 0 } == true)
        }
    }
}

internal class ConsolidationLimit : Exception()
internal class ConsolidationCacheUnfinished : Exception()
