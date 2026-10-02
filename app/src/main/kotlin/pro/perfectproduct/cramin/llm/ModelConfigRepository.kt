package pro.perfectproduct.cramin.llm

import pro.perfectproduct.cramin.util.useCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import pro.perfectproduct.cramin.data.prefs.SettingsStore
import pro.perfectproduct.cramin.util.Clock
import pro.perfectproduct.cramin.util.Log
import java.io.IOException

/**
 * Эффективный конфиг моделей (SPEC §6.12): настройки > удалённый `config/models.json` (ETag) > вшитая копия.
 * Удалённый конфиг обновляется в начале каждой обработки и по кнопке; ошибка сети не блокирует работу.
 */
class ModelConfigRepository(
    private val http: OkHttpClient,
    private val settings: SettingsStore,
    private val catalog: ModelCatalog,
    private val embeddedJsonProvider: () -> String,
    private val clock: Clock,
    private val remoteUrl: String = REMOTE_URL,
) {
    val embedded: ModelsConfigFile by lazy {
        ModelsConfigFile.parseOrNull(embeddedJsonProvider()) ?: error("встроенный config/models.json невалиден")
    }

    /** Меняется после обновления каталога, чтобы экран настроек пересчитал эффективный конфиг. */
    private val catalogVersion = MutableStateFlow(0)

    /** Эффективный конфиг без сети: кэшированный каталог и кэшированный удалённый конфиг. */
    suspend fun effective(): EffectiveConfig {
        val s = settings.current()
        val remote = s.remoteModelsJson?.let { ModelsConfigFile.parseOrNull(it) }
        return ModelConfigResolver.resolve(ModelOverrides.parse(s.modelOverridesJson), remote, embedded, catalog.cached())
    }

    /** Обновляет удалённый конфиг и каталог (если устарел) и возвращает эффективный конфиг. Ошибки сети глотаются. */
    suspend fun refreshAndResolve(forceCatalog: Boolean = false): EffectiveConfig {
        refreshRemote()
        catalog.get(force = forceCatalog)
        catalogVersion.value++
        return effective()
    }

    fun observeEffective(): Flow<EffectiveConfig> = combine(settings.settings, catalogVersion) { s, _ ->
        val remote = s.remoteModelsJson?.let { ModelsConfigFile.parseOrNull(it) }
        ModelConfigResolver.resolve(ModelOverrides.parse(s.modelOverridesJson), remote, embedded, catalog.cached())
    }

    fun observeRemoteUpdatedAt(): Flow<Long> = settings.settings.map { it.remoteModelsUpdatedAt }

    suspend fun setOverride(role: ModelRole, override: RoleOverride?) {
        val current = ModelOverrides.parse(settings.current().modelOverridesJson)
        val next = current.with(role, override)
        settings.setModelOverridesJson(if (next.roles.isEmpty()) null else ModelOverrides.encode(next))
    }

    suspend fun clearOverrides() = settings.setModelOverridesJson(null)

    /** `GET` удалённого конфига с `If-None-Match`; 304 — оставить кэш; невалидный JSON или чужая схема — игнорировать. */
    suspend fun refreshRemote(): Boolean = withContext(Dispatchers.IO) {
        val s = settings.current()
        try {
            val builder = Request.Builder().url(remoteUrl)
            s.remoteModelsEtag?.let { builder.header("If-None-Match", it) }
            http.newCall(builder.build()).useCancellable { resp ->
                when {
                    resp.code == 304 -> {
                        settings.setRemoteModels(s.remoteModelsJson, s.remoteModelsEtag, clock.now())
                        true
                    }
                    resp.isSuccessful -> {
                        val text = resp.body.string()
                        if (ModelsConfigFile.parseOrNull(text) == null) {
                            Log.w(TAG, "remote config ignored: invalid or unknown schemaVersion")
                            false
                        } else {
                            settings.setRemoteModels(text, resp.header("ETag"), clock.now())
                            Log.i(TAG, "remote config updated")
                            true
                        }
                    }
                    else -> {
                        Log.w(TAG, "remote config HTTP ${resp.code}")
                        false
                    }
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "remote config fetch failed: ${e.javaClass.simpleName}")
            false
        }
    }

    companion object {
        private const val TAG = "ModelConfig"
        const val REMOTE_URL = "https://raw.githubusercontent.com/PerfectProduct/cramin/main/config/models.json"
    }
}
