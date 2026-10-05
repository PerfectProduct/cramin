package pro.perfectproduct.cramin.testing

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import pro.perfectproduct.cramin.app.AppContainer
import pro.perfectproduct.cramin.data.db.CraminDatabase
import pro.perfectproduct.cramin.data.prefs.SecretCipher
import pro.perfectproduct.cramin.llm.EffectiveConfig
import pro.perfectproduct.cramin.llm.LlmClient
import pro.perfectproduct.cramin.llm.ModelConfigResolver
import pro.perfectproduct.cramin.llm.ModelsConfigFile
import pro.perfectproduct.cramin.llm.PipelineParamsJson
import pro.perfectproduct.cramin.llm.RoleConfig
import pro.perfectproduct.cramin.pipeline.ProcessorDeps
import java.io.File

/** Обратимый «шифр» для инструментированных тестов без Keystore. */
class PlainCipher : SecretCipher {
    override fun encrypt(plain: ByteArray): ByteArray = ByteArray(12) + plain
    override fun decrypt(blob: ByteArray): ByteArray? = if (blob.size >= 12) blob.copyOfRange(12, blob.size) else null
}

/**
 * Контейнер для инструментированных тестов: БД в памяти, DataStore во временных файлах,
 * фейковая LLM и фиксированный конфиг моделей — без сети и без Keystore.
 */
class TestContainer(
    context: Context,
    val fakeLlm: FakeLlmClient = FakeLlmClient(),
    val config: ModelsConfigFile = FAKE_CONFIG,
    private val dir: File = File(context.cacheDir, "test-${System.nanoTime()}").apply { mkdirs() },
    databaseProvider: () -> CraminDatabase = { CraminDatabase.inMemory(context) },
) : AppContainer(
    appContext = context,
    databaseProvider = databaseProvider,
    settingsDataStoreProvider = { scope -> PreferenceDataStoreFactory.create(scope = scope) { File(dir, "settings.preferences_pb") } },
    secretsDataStoreProvider = { scope -> PreferenceDataStoreFactory.create(scope = scope) { File(dir, "secrets.preferences_pb") } },
    secretCipher = PlainCipher(),
) {
    override val llmClient: LlmClient get() = fakeLlm

    val effective: EffectiveConfig by lazy { ModelConfigResolver.resolve(null, null, config, null) }

    override fun processorDeps(): ProcessorDeps = super.processorDeps().let {
        ProcessorDeps(
            db = it.db, files = it.files, llm = fakeLlm, extractors = it.extractors, transcriber = it.transcriber,
            audioSegmenter = it.audioSegmenter, configProvider = { effective }, catalogProvider = { null },
            stoplists = it.stoplists, segmenter = it.segmenter, usage = it.usage, clock = it.clock,
        )
    }

    companion object {
        val FAKE_CONFIG = ModelsConfigFile(
            schemaVersion = 1,
            roles = mapOf(
                "brief" to RoleConfig("fake/brief", 0.2, 6000),
                "translate" to RoleConfig("fake/translate", 0.3, null),
                "extract" to RoleConfig("fake/extract", 0.1, 8000),
                "consolidate" to RoleConfig("fake/consolidate", 0.1, 4000),
                "stt" to RoleConfig("fake/stt"),
                "topic" to RoleConfig("fake/topic", 0.1, null, kotlinx.serialization.json.buildJsonObject { put("effort", kotlinx.serialization.json.JsonPrimitive("low")) }),
            ),
            pipeline = PipelineParamsJson(60_000, 120, 80, 3, mapOf("en" to 1.4, "ru" to 2.6, "he" to 2.6)),
        )
    }
}
