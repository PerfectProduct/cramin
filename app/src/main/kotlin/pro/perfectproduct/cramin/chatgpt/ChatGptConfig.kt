package pro.perfectproduct.cramin.chatgpt

import pro.perfectproduct.cramin.llm.*

/** Plan-specific settings are independent of OpenRouter remote/model overrides. */
object ChatGptConfig {
    fun resolve(slug: String, embedded: ModelsConfigFile): EffectiveConfig {
        require(slug.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")))
        val roles = ModelRole.entries.associateWith { role ->
            if (role.isText) EffectiveRole(role, slug, null, null, ConfigSource.OVERRIDE, null, TextProvider.CHATGPT_PLAN)
            else embedded.roles.getValue(role.key).let { r -> EffectiveRole(role, r.model, r.temperature, r.maxTokens, ConfigSource.EMBEDDED, r.reasoning) }
        }
        return EffectiveConfig(roles, ModelConfigResolver.mergePipeline(null, embedded.pipeline).copy(extractChunkWords = 350),
            emptyList(), TextProvider.CHATGPT_PLAN)
    }
}
