package pro.perfectproduct.cramin.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Immutable configuration for one processing run, including catalog-dependent limits. */
@Serializable
data class ProcessingSnapshot(
    val version: Int = 1,
    val config: EffectiveConfig,
    val catalog: List<CatalogModel> = emptyList(),
    val legacyParametersUnknown: Boolean = false,
) : CatalogView {
    override fun find(modelId: String): CatalogModel? = catalog.firstOrNull { it.id == modelId }
    fun encode(): String = json.encodeToString(this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun capture(config: EffectiveConfig, catalog: CatalogView?): ProcessingSnapshot = ProcessingSnapshot(
            config = config,
            catalog = config.roles.values.mapNotNull { catalog?.find(it.model) }.distinctBy { it.id },
        )
        fun decode(text: String): ProcessingSnapshot {
            val root = json.parseToJsonElement(text).jsonObject
            if ("version" in root) return json.decodeFromString<ProcessingSnapshot>(text).also { require(it.version == 1) }
            // Installed versions stored roles only. Never pretend to recover missing historical parameters.
            val roles = json.decodeFromString<Map<String, RoleConfig>>(text).mapNotNull { (key, value) ->
                ModelRole.fromKey(key)?.let { role -> role to EffectiveRole(role, value.model, value.temperature, value.maxTokens, ConfigSource.EMBEDDED, value.reasoning) }
            }.toMap()
            require(roles.isNotEmpty())
            return ProcessingSnapshot(config = EffectiveConfig(roles, PipelineParams(), listOf("Legacy snapshot: historical pipeline parameters unavailable")), legacyParametersUnknown = true)
        }
    }
}
