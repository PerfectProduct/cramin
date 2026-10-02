package pro.perfectproduct.cramin.testing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import pro.perfectproduct.cramin.data.db.CraminDatabase
import pro.perfectproduct.cramin.data.db.SourceType
import pro.perfectproduct.cramin.data.repo.CardRepository
import pro.perfectproduct.cramin.data.repo.DocumentFiles
import pro.perfectproduct.cramin.data.repo.DocumentRepository
import pro.perfectproduct.cramin.data.repo.UsageRepository
import pro.perfectproduct.cramin.ingest.PlainTextExtractor
import pro.perfectproduct.cramin.ingest.SourceExtractor
import pro.perfectproduct.cramin.llm.CatalogView
import pro.perfectproduct.cramin.llm.EffectiveConfig
import pro.perfectproduct.cramin.llm.LlmClient
import pro.perfectproduct.cramin.llm.ModelConfigResolver
import pro.perfectproduct.cramin.llm.ModelsConfigFile
import pro.perfectproduct.cramin.llm.PipelineParamsJson
import pro.perfectproduct.cramin.llm.RoleConfig
import pro.perfectproduct.cramin.pipeline.DocumentProcessor
import pro.perfectproduct.cramin.pipeline.Icu4jSentenceBreaker
import pro.perfectproduct.cramin.pipeline.ProcessorDeps
import pro.perfectproduct.cramin.pipeline.Segmenter
import pro.perfectproduct.cramin.pipeline.TestStoplists
import pro.perfectproduct.cramin.transcribe.AudioSegmenter
import pro.perfectproduct.cramin.transcribe.Transcriber
import pro.perfectproduct.cramin.util.Clock
import java.io.File

/** Сборка пайплайна для JVM-тестов (Robolectric): БД в памяти, файлы во временном каталоге, фейковые модели. */
class TestPipeline(
    root: File,
    val llm: LlmClient,
    val config: ModelsConfigFile = FAKE_CONFIG,
    val catalog: CatalogView? = null,
    extraExtractors: Map<SourceType, SourceExtractor> = emptyMap(),
    val transcriber: Transcriber? = null,
    val audioSegmenter: AudioSegmenter? = null,
    val checkpoint: (String) -> Unit = {},
) : AutoCloseable {
    val context: Context = ApplicationProvider.getApplicationContext()
    val db: CraminDatabase = CraminDatabase.inMemory(context)
    val files = DocumentFiles(File(root, "files"))
    val clock = Clock { System.currentTimeMillis() }
    val documents = DocumentRepository(db, files, clock)
    val cards = CardRepository(db, clock)
    val usage = UsageRepository(db, clock)
    var effective: EffectiveConfig = ModelConfigResolver.resolve(null, null, config, catalog)

    val deps = ProcessorDeps(
        db = db,
        files = files,
        llm = llm,
        extractors = mapOf(SourceType.TEXT to PlainTextExtractor()) + extraExtractors,
        transcriber = transcriber,
        audioSegmenter = audioSegmenter,
        configProvider = { effective },
        catalogProvider = { catalog },
        stoplists = TestStoplists.instance,
        segmenter = Segmenter(Icu4jSentenceBreaker()),
        usage = usage,
        clock = clock,
        checkpoint = checkpoint,
    )

    fun processor() = DocumentProcessor(deps)

    override fun close() = db.close()

    companion object {
        /** Фейковые id моделей (SPEC: в тестах с фейком — фейковые id) и маленькие секции/чанки ради нескольких задач. */
        val FAKE_CONFIG = ModelsConfigFile(
            schemaVersion = 1,
            roles = mapOf(
                "brief" to RoleConfig("fake/brief", 0.2, 6000),
                "translate" to RoleConfig("fake/translate", 0.3, null),
                "extract" to RoleConfig("fake/extract", 0.1, 8000),
                "consolidate" to RoleConfig("fake/consolidate", 0.1, 4000),
                "stt" to RoleConfig("fake/stt"),
            ),
            pipeline = PipelineParamsJson(
                briefMaxInputWords = 60_000,
                translateMaxSectionWords = 120,
                extractChunkWords = 80,
                extractConcurrency = 3,
                tokensPerWord = mapOf("en" to 1.4, "ru" to 2.6, "he" to 2.6),
            ),
        )
    }
}
