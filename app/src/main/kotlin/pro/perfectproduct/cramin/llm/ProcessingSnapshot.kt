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
    val legacyCapabilitiesResolved: Boolean = false,
) : CatalogView {
    override fun find(modelId: String): CatalogModel? = catalog.firstOrNull { it.id == modelId }
    /** Fill only historically absent metadata, once; never replace saved role settings/models. */
    fun resolveLegacyCapabilities(current: CatalogView?): ProcessingSnapshot {
        if (!legacyParametersUnknown || legacyCapabilitiesResolved || current == null) return this
        val enriched = (catalog + config.roles.values.mapNotNull { current.find(it.model) }).distinctBy { it.id }
        return copy(catalog = enriched, legacyCapabilitiesResolved = config.roles.values.filter { it.role.isText }
            .all { role -> enriched.any { it.id == role.model } })
    }
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
