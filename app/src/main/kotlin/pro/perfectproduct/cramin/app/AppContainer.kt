package pro.perfectproduct.cramin.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import pro.perfectproduct.cramin.BuildConfig
import pro.perfectproduct.cramin.data.db.CraminDatabase
import pro.perfectproduct.cramin.data.db.SourceType
import pro.perfectproduct.cramin.data.prefs.KeystoreCipher
import pro.perfectproduct.cramin.data.prefs.SecretCipher
import pro.perfectproduct.cramin.data.prefs.SecretStore
import pro.perfectproduct.cramin.data.prefs.SettingsStore
import pro.perfectproduct.cramin.data.repo.CardRepository
import pro.perfectproduct.cramin.data.repo.DocumentFiles
import pro.perfectproduct.cramin.data.repo.DocumentRepository
import pro.perfectproduct.cramin.data.repo.StudyRepository
import pro.perfectproduct.cramin.data.repo.UsageRepository
import pro.perfectproduct.cramin.ingest.ArticleExtractor
import pro.perfectproduct.cramin.ingest.PdfExtractor
import pro.perfectproduct.cramin.ingest.PlainTextExtractor
import pro.perfectproduct.cramin.ingest.YoutubeExtractor
import pro.perfectproduct.cramin.ingest.SourceExtractor
import pro.perfectproduct.cramin.llm.KeyChecker
import pro.perfectproduct.cramin.llm.LlmClient
import pro.perfectproduct.cramin.llm.ModelCatalog
import pro.perfectproduct.cramin.llm.ModelConfigRepository
import pro.perfectproduct.cramin.llm.OpenRouterClient
import pro.perfectproduct.cramin.pipeline.AndroidSentenceBreaker
import pro.perfectproduct.cramin.pipeline.DocumentProcessor
import pro.perfectproduct.cramin.pipeline.ProcessDocumentWorker
import pro.perfectproduct.cramin.pipeline.ProcessScheduler
import pro.perfectproduct.cramin.pipeline.ProcessorDeps
import pro.perfectproduct.cramin.pipeline.Segmenter
import pro.perfectproduct.cramin.pipeline.Stoplists
import pro.perfectproduct.cramin.transcribe.AudioSegmenter
import pro.perfectproduct.cramin.transcribe.MediaAudioSegmenter
import pro.perfectproduct.cramin.transcribe.OpenRouterSttTranscriber
import pro.perfectproduct.cramin.transcribe.Transcriber
import pro.perfectproduct.cramin.util.Clock
import pro.perfectproduct.cramin.util.Lang
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Ручной DI-контейнер (SPEC §5.1). Всё создаётся лениво и живёт, пока жив процесс.
 * Тесты собирают контейнер с фейками через конструктор (open-свойства подменяются наследником).
 */
open class AppContainer(
    val appContext: Context,
    val clock: Clock = Clock.SYSTEM,
    databaseProvider: () -> CraminDatabase = { CraminDatabase.build(appContext) },
    settingsDataStoreProvider: (CoroutineScope) -> DataStore<Preferences> = { scope ->
        PreferenceDataStoreFactory.create(scope = scope) { appContext.preferencesDataStoreFile("settings") }
    },
    secretsDataStoreProvider: (CoroutineScope) -> DataStore<Preferences> = { scope ->
        PreferenceDataStoreFactory.create(scope = scope) { appContext.preferencesDataStoreFile(SecretStore.DATASTORE_NAME) }
    },
    secretCipher: SecretCipher = KeystoreCipher(),
) {
    /** Область для фоновых задач контейнера (DataStore, кэши). Живёт столько же, сколько процесс. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val db: CraminDatabase by lazy(databaseProvider)
    val files: DocumentFiles by lazy { DocumentFiles(appContext.filesDir) }

    val settingsStore: SettingsStore by lazy { SettingsStore(settingsDataStoreProvider(appScope)) }
    val secretStore: SecretStore by lazy { SecretStore(secretsDataStoreProvider(appScope), secretCipher) }

    val documentRepository: DocumentRepository by lazy { DocumentRepository(db, files, clock) }
    val cardRepository: CardRepository by lazy { CardRepository(db, clock) }
    val studyRepository: StudyRepository by lazy { StudyRepository(db, clock) }
    val usageRepository: UsageRepository by lazy { UsageRepository(db, clock) }

    // --- Сеть и модели -----------------------------------------------------------------------
    open val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .apply { if (BuildConfig.DEBUG) HttpLogging.install(this) }
            .build()
    }

    open val llmClient: LlmClient by lazy {
        OpenRouterClient(
            httpClient,
            keyProvider = { secretStore.getApiKey() },
            paramSupport = { model -> modelCatalog.cached()?.find(model)?.supportedParameters?.toSet() },
        )
    }
    val keyChecker: KeyChecker by lazy { KeyChecker(httpClient) }
    val modelCatalog: ModelCatalog by lazy { ModelCatalog(httpClient, File(appContext.filesDir, "cache/${ModelCatalog.CACHE_FILE_NAME}"), clock) }
    val modelConfigRepository: ModelConfigRepository by lazy {
        ModelConfigRepository(httpClient, settingsStore, modelCatalog, embeddedJsonProvider = { readEmbeddedModelsJson() }, clock = clock)
    }

    // --- Пайплайн ------------------------------------------------------------------------------
    val stoplists: Stoplists by lazy {
        Stoplists { lang ->
            val resId = when (lang) {
                Lang.EN -> pro.perfectproduct.cramin.R.raw.stop_en
                Lang.RU -> pro.perfectproduct.cramin.R.raw.stop_ru
                Lang.HE -> pro.perfectproduct.cramin.R.raw.stop_he
            }
            appContext.resources.openRawResource(resId).use { it.readBytes().toString(Charsets.UTF_8) }
        }
    }
    val segmenter: Segmenter by lazy { Segmenter(AndroidSentenceBreaker()) }

    /** Источники по типам (SPEC §7). */
    open val extractors: Map<SourceType, SourceExtractor> by lazy {
        mapOf(
            SourceType.TEXT to PlainTextExtractor(),
            SourceType.URL to ArticleExtractor(httpClient),
            SourceType.YOUTUBE to YoutubeExtractor(httpClient),
            SourceType.PDF to PdfExtractor(appContext),
        )
    }
    open val transcriber: Transcriber? by lazy { OpenRouterSttTranscriber(httpClient, keyProvider = { secretStore.getApiKey() }) }
    open val audioSegmenter: AudioSegmenter? by lazy { MediaAudioSegmenter() }

    open fun processorDeps(): ProcessorDeps = ProcessorDeps(
        db = db,
        files = files,
        llm = llmClient,
        extractors = extractors,
        transcriber = transcriber,
        audioSegmenter = audioSegmenter,
        configProvider = { modelConfigRepository.refreshAndResolve() },
        catalogProvider = { modelCatalog.cached() },
        stoplists = stoplists,
        segmenter = segmenter,
        usage = usageRepository,
        clock = clock,
    )

    fun newProcessor(): DocumentProcessor = DocumentProcessor(processorDeps())

    /** TTS (SPEC §10.5); создаётся лениво при первом обращении из UI. */
    open val tts: pro.perfectproduct.cramin.study.TtsController by lazy { pro.perfectproduct.cramin.study.TtsController(appContext) }

    // --- Обновления (SPEC §12.4), изолированный пакет update/ ---------------------------------
    val apkInstaller: pro.perfectproduct.cramin.update.ApkInstaller by lazy { pro.perfectproduct.cramin.update.ApkInstaller(appContext) }
    open val updateManager: pro.perfectproduct.cramin.update.UpdateManager by lazy {
        pro.perfectproduct.cramin.update.UpdateManager(
            checker = pro.perfectproduct.cramin.update.UpdateChecker(httpClient, BuildConfig.VERSION_CODE),
            downloader = pro.perfectproduct.cramin.update.ApkDownloader(httpClient, appContext.cacheDir),
            installer = apkInstaller,
            scope = appScope,
        )
    }

    val workManager: WorkManager by lazy { WorkManager.getInstance(appContext) }
    val processScheduler: ProcessScheduler by lazy { ProcessScheduler(workManager) }

    /** Фабрика воркеров WorkManager: воркер получает процессор из контейнера. */
    open val workerFactory: WorkerFactory = object : WorkerFactory() {
        override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
            when (workerClassName) {
                ProcessDocumentWorker::class.java.name -> ProcessDocumentWorker(
                    appContext, workerParameters,
                    processorFactory = { newProcessor() },
                    titleProvider = { id -> documentRepository.get(id)?.title },
                )
                else -> null
            }
    }

    private fun readEmbeddedModelsJson(): String =
        appContext.assets.open(EMBEDDED_MODELS_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }

    companion object {
        const val EMBEDDED_MODELS_ASSET = "models.json"

        /** Длинные ответы моделей: чтение до 5 минут. */
        const val READ_TIMEOUT_SECONDS = 300L

        fun create(context: Context): AppContainer = AppContainer(context.applicationContext)
    }
}
