package pro.perfectproduct.cramin.pipeline

import pro.perfectproduct.cramin.llm.*
import pro.perfectproduct.cramin.util.Lang

/** Conservative byte-based proxy, NOT the model's tokenizer or a guarantee about provider templates. */
object BriefRequestFactory {
    const val UNKNOWN_CONTEXT_BUDGET = 32768
    const val PROTOCOL_MARGIN = 1024
    const val DEFAULT_OUTPUT = 6000

    fun build(paragraphs: List<String>, lang: Lang, target: Lang, config: EffectiveConfig, catalog: CatalogView?): LlmRequest {
        val role = config.role(ModelRole.BRIEF)
        val model = catalog?.find(role.model)
        val output = role.maxTokens ?: minOf(DEFAULT_OUTPUT, model?.maxCompletionTokens?.takeIf { it > 0 } ?: DEFAULT_OUTPUT)
        val context = model?.contextLength?.takeIf { it > 0 } ?: UNKNOWN_CONTEXT_BUDGET
        if (output <= 0 || output >= context || (model?.maxCompletionTokens?.let { it > 0 && output > it } == true))
            throw LlmException.BadRequest(0, RequestRejection.LOCAL_BUDGET.name)
        val selected = BriefInput.select(paragraphs, config.pipeline.briefMaxInputWords)
        fun request(input: String) = LlmRequest(ModelRole.BRIEF, role.model, Prompts.BRIEF,
            Messages.brief(lang, target, input), Schemas.BRIEF_NAME, Schemas.BRIEF,
            role.temperature, output, role.reasoning, provider = role.provider)
        fun fits(input: String): Boolean = ChatRequestBody.forSizing(request(input), model?.supportedParameters?.toSet(), true)
            .toString().toByteArray(Charsets.UTF_8).size.toLong() + output + PROTOCOL_MARGIN <= context.toLong()
        if (fits(selected)) return request(selected)
        if (!fits("")) throw LlmException.BadRequest(0, RequestRejection.LOCAL_BUDGET.name)
        // Prefix of an already sampled document; do not cut a surrogate pair.
        fun prefix(length: Int): String {
            val safe = if (length > 0 && length < selected.length && selected[length - 1].isHighSurrogate()) length - 1 else length
            return selected.take(safe)
        }
        var lo = 0
        var hi = selected.length
        while (lo < hi) {
            val mid = lo + (hi - lo + 1) / 2
            if (fits(prefix(mid))) lo = mid else hi = mid - 1
        }
        var input = prefix(lo).trimEnd()
        val boundary = input.indexOfLast { it.isWhitespace() }
        if (boundary > 0) input = input.take(boundary).trimEnd()
        if (input.isBlank()) throw LlmException.BadRequest(0, RequestRejection.LOCAL_BUDGET.name)
        return request(input)
    }
}
