package pro.perfectproduct.cramin.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Конфиг моделей `config/models.json` (SPEC §6.12): роли, параметры пайплайна, схема v1.
 * Ни одного id модели в коде: значения приходят только из файла, удалённой копии и настроек.
 */
@Serializable
data class RoleConfig(
    val model: String,
    val temperature: Double? = null,
    /** null — «брать max_completion_tokens из каталога». */
    val maxTokens: Int? = null,
    /**
     * Объект `reasoning` OpenRouter, передаётся в запрос как есть (например `{"effort":"low"}`),
     * чтобы рассуждающие модели не тратили токены на размышления. Расширение схемы v1 (CRM-DL-012).
     */
    val reasoning: JsonObject? = null,
)

@Serializable
data class PipelineParamsJson(
    @SerialName("brief.maxInputWords") val briefMaxInputWords: Int? = null,
    @SerialName("translate.maxSectionWords") val translateMaxSectionWords: Int? = null,
    @SerialName("extract.chunkWords") val extractChunkWords: Int? = null,
    @SerialName("extract.concurrency") val extractConcurrency: Int? = null,
    val tokensPerWord: Map<String, Double>? = null,
)

@Serializable
data class ModelsConfigFile(
    val schemaVersion: Int,
    val updatedAt: String? = null,
    val roles: Map<String, RoleConfig> = emptyMap(),
    val pipeline: PipelineParamsJson? = null,
) {
    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1

        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        /** Возвращает null, если JSON не разбирается или версия схемы неизвестна (SPEC §6.12 «Валидация»). */
        fun parseOrNull(text: String): ModelsConfigFile? {
            val cfg = runCatching { json.decodeFromString<ModelsConfigFile>(text) }.getOrNull() ?: return null
            return cfg.takeIf { it.schemaVersion == SUPPORTED_SCHEMA_VERSION }
        }

        fun parse(text: String): ModelsConfigFile = json.decodeFromString(text)
    }
}

/** Переопределения пользователя (SPEC §6.12, источник 1): по ролям и отдельным параметрам. */
@Serializable
data class RoleOverride(
    val model: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
) {
    val isEmpty: Boolean get() = model == null && temperature == null && maxTokens == null
}

@Serializable
data class ModelOverrides(val roles: Map<String, RoleOverride> = emptyMap()) {
    fun with(role: ModelRole, override: RoleOverride?): ModelOverrides {
        val m = roles.toMutableMap()
        if (override == null || override.isEmpty) m.remove(role.key) else m[role.key] = override
        return ModelOverrides(m)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        fun parse(text: String?): ModelOverrides = text?.let { runCatching { json.decodeFromString<ModelOverrides>(it) }.getOrNull() } ?: ModelOverrides()
        fun encode(o: ModelOverrides): String = json.encodeToString(o)
    }
}

enum class ConfigSource { OVERRIDE, REMOTE, EMBEDDED }

@Serializable
data class PipelineParams(
    val briefMaxInputWords: Int = 60_000,
    val translateMaxSectionWords: Int = 4_000,
    val extractChunkWords: Int = 700,
    val extractConcurrency: Int = 3,
    val tokensPerWord: Map<String, Double> = mapOf("en" to 1.4, "ru" to 2.6, "he" to 2.6),
) {
    fun tokensPerWord(langCode: String): Double = tokensPerWord[langCode] ?: DEFAULT_TOKENS_PER_WORD

    companion object {
        const val DEFAULT_TOKENS_PER_WORD = 2.6
    }
}

@Serializable
data class EffectiveRole(
    val role: ModelRole,
    val model: String,
    val temperature: Double?,
    val maxTokens: Int?,
    /** Откуда взята модель: «своя» / «конфиг репозитория» / «встроенный» (SPEC §9.7). */
    val source: ConfigSource,
    val reasoning: JsonObject? = null,
)

@Serializable
data class EffectiveConfig(
    val roles: Map<ModelRole, EffectiveRole>,
    val pipeline: PipelineParams,
    /** Предупреждения для экрана настроек: отклонённые модели, игнорированный удалённый конфиг. */
    val warnings: List<String>,
) {
    fun role(role: ModelRole): EffectiveRole = roles[role] ?: error("role ${role.key} is not configured")

    /** Снимок ролей для Document.modelsSnapshotJson. */
    fun snapshotJson(): String = Json.encodeToString(roles.values.associate { it.role.key to RoleConfig(it.model, it.temperature, it.maxTokens, it.reasoning) })
}

/** Минимальное представление каталога для валидации конфига. */
interface CatalogView {
    fun find(modelId: String): CatalogModel?
}

@Serializable
data class CatalogModel(
    val id: String,
    val name: String,
    val contextLength: Int?,
    val maxCompletionTokens: Int?,
    /** Цена за токен (USD), как в каталоге OpenRouter (строки → числа). */
    val promptPrice: Double?,
    val completionPrice: Double?,
    val supportedParameters: List<String>,
    val inputModalities: List<String>,
    val outputModalities: List<String>,
) {
    /** SPEC §6.12: текстовая роль требует structured_outputs или response_format. */
    val supportsStructuredOutputs: Boolean
        get() = "structured_outputs" in supportedParameters || "response_format" in supportedParameters

    /** Цена за миллион токенов для сортировки и показа. */
    val promptPricePerMillion: Double? get() = promptPrice?.let { it * 1_000_000 }
    val completionPricePerMillion: Double? get() = completionPrice?.let { it * 1_000_000 }
}

/** Слияние источников конфига и валидация по каталогу (SPEC §6.12). */
object ModelConfigResolver {

    fun resolve(
        overrides: ModelOverrides?,
        remote: ModelsConfigFile?,
        embedded: ModelsConfigFile,
        catalog: CatalogView?,
    ): EffectiveConfig {
        val warnings = mutableListOf<String>()
        val roles = LinkedHashMap<ModelRole, EffectiveRole>()
        for (role in ModelRole.entries) {
            val candidates = buildList {
                overrides?.roles?.get(role.key)?.model?.let { add(Triple(ConfigSource.OVERRIDE, it, overrides.roles.getValue(role.key).let { o -> RoleConfig(it, o.temperature, o.maxTokens) })) }
                remote?.roles?.get(role.key)?.let { add(Triple(ConfigSource.REMOTE, it.model, it)) }
                embedded.roles[role.key]?.let { add(Triple(ConfigSource.EMBEDDED, it.model, it)) }
            }
            if (candidates.isEmpty()) {
                warnings.add("Роль «${role.key}» не задана ни в одном источнике конфига.")
                continue
            }
            var chosen: Triple<ConfigSource, String, RoleConfig>? = null
            for (c in candidates) {
                val rejection = reject(role, c.second, catalog)
                if (rejection == null) {
                    chosen = c
                    break
                }
                warnings.add("Модель «${c.second}» для роли «${role.key}» (${sourceLabel(c.first)}) отклонена: $rejection")
            }
            // Если каталог отверг все источники, берём последний (встроенный): без модели работать нельзя,
            // а пользователь увидит предупреждение на экране настроек.
            val pick = chosen ?: candidates.last()
            val o = overrides?.roles?.get(role.key)
            val base = pick.third
            val fallback = candidates.firstOrNull { it.first != ConfigSource.OVERRIDE }?.third
            roles[role] = EffectiveRole(
                role = role,
                model = pick.second,
                temperature = o?.temperature ?: base.temperature ?: fallback?.temperature,
                maxTokens = o?.maxTokens ?: base.maxTokens ?: if (pick.first == ConfigSource.OVERRIDE) fallback?.maxTokens else null,
                source = pick.first,
                // Параметры рассуждения привязаны к модели: для своей модели их нет, если она не совпала с конфигом.
                reasoning = if (pick.first == ConfigSource.OVERRIDE) fallback?.takeIf { it.model == pick.second }?.reasoning else base.reasoning,
            )
        }
        val pipeline = mergePipeline(remote?.pipeline, embedded.pipeline)
        return EffectiveConfig(roles, pipeline, warnings)
    }

    /** Причина отклонения модели или null, если модель принята. */
    fun reject(role: ModelRole, modelId: String, catalog: CatalogView?): String? {
        // STT-модели живут на другом эндпоинте и в каталоге чата не проверяются.
        if (catalog == null || !role.isText) return null
        val m = catalog.find(modelId) ?: return "модели нет в каталоге OpenRouter"
        if (!m.supportsStructuredOutputs) return "нет structured outputs"
        return null
    }

    fun mergePipeline(remote: PipelineParamsJson?, embedded: PipelineParamsJson?): PipelineParams {
        val d = PipelineParams()
        return PipelineParams(
            briefMaxInputWords = remote?.briefMaxInputWords ?: embedded?.briefMaxInputWords ?: d.briefMaxInputWords,
            translateMaxSectionWords = remote?.translateMaxSectionWords ?: embedded?.translateMaxSectionWords ?: d.translateMaxSectionWords,
            extractChunkWords = remote?.extractChunkWords ?: embedded?.extractChunkWords ?: d.extractChunkWords,
            extractConcurrency = remote?.extractConcurrency ?: embedded?.extractConcurrency ?: d.extractConcurrency,
            tokensPerWord = remote?.tokensPerWord ?: embedded?.tokensPerWord ?: d.tokensPerWord,
        )
    }

    fun sourceLabel(source: ConfigSource): String = when (source) {
        ConfigSource.OVERRIDE -> "своя"
        ConfigSource.REMOTE -> "конфиг репозитория"
        ConfigSource.EMBEDDED -> "встроенный"
    }
}
