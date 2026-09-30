package pro.perfectproduct.cramin.live

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import pro.perfectproduct.cramin.llm.LlmClient
import pro.perfectproduct.cramin.llm.LlmException
import pro.perfectproduct.cramin.llm.LlmRequest
import pro.perfectproduct.cramin.llm.LlmResponse
import pro.perfectproduct.cramin.llm.ModelCatalog
import pro.perfectproduct.cramin.llm.ModelsConfigFile
import pro.perfectproduct.cramin.llm.OpenRouterClient
import pro.perfectproduct.cramin.util.Clock
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Окружение живых тестов (SPEC §14.1): ключ и бюджет из переменных окружения, которые Gradle
 * берёт из .env только при -Plive=true. Без ключа тесты пропускаются. Ключ никуда не печатается.
 */
object LiveEnv {
    val apiKey: String? = System.getenv("OPENROUTER_API_KEY")?.trim()?.takeIf { it.isNotEmpty() }
    val budgetUsd: Double = System.getenv("LIVE_BUDGET_USD")?.trim()?.toDoubleOrNull() ?: 1.0
    val reportDir: File = File(System.getProperty("cramin.liveReportDir") ?: "build/reports/live").apply { mkdirs() }
    val bakeoffRequested: Boolean = System.getProperty("cramin.bakeoff") == "true"

    /** Корень репозитория (config/models.json) — для чтения конфига и записи материалов бейкоффа. */
    val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "config/models.json").isFile }

    fun requireKey(): String {
        assumeTrue("OPENROUTER_API_KEY не задан — живые тесты пропущены", apiKey != null)
        return apiKey!!
    }

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(300, TimeUnit.SECONDS).build()
    }

    val catalog: ModelCatalog by lazy { ModelCatalog(http, File(reportDir, "models-catalog.json"), Clock.SYSTEM) }

    val repoConfig: ModelsConfigFile by lazy { ModelsConfigFile.parse(File(repoRoot, "config/models.json").readText()) }

    /** Реальный клиент с учётом бюджета. */
    fun client(): LlmClient = BudgetedLlmClient(
        OpenRouterClient(http, keyProvider = { requireKey() }, paramSupport = { m -> catalog.cached()?.find(m)?.supportedParameters?.toSet() }),
        LiveBudget,
    )
}

/**
 * Суммарная фактическая стоимость прогона по `usage.cost` (SPEC §14.1). Пишется в
 * `build/reports/live/live-cost.json` после каждого вызова; при превышении LIVE_BUDGET_USD
 * следующий вызов бросает BudgetExceeded, и прогон останавливается с понятной ошибкой.
 */
object LiveBudget {
    private val file = File(LiveEnv.reportDir, "live-cost.json")

    @Volatile
    var spentUsd: Double = 0.0
        private set

    @Volatile
    var calls: Int = 0
        private set

    private val perModel = LinkedHashMap<String, Double>()

    @Synchronized
    fun record(model: String, costUsd: Double?) {
        calls++
        val c = costUsd ?: 0.0
        spentUsd += c
        perModel[model] = (perModel[model] ?: 0.0) + c
        file.writeText(
            buildJsonObject {
                put("spentUsd", spentUsd)
                put("budgetUsd", LiveEnv.budgetUsd)
                put("calls", calls)
                put("perModel", kotlinx.serialization.json.JsonObject(perModel.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }))
            }.toString(),
        )
    }

    @Synchronized
    fun check() {
        if (spentUsd > LiveEnv.budgetUsd) throw LlmException.BudgetExceeded(spentUsd, LiveEnv.budgetUsd)
    }
}

class BudgetedLlmClient(private val inner: LlmClient, private val budget: LiveBudget) : LlmClient {
    override suspend fun complete(request: LlmRequest): LlmResponse {
        budget.check()
        val r = inner.complete(request)
        budget.record(r.model.ifEmpty { request.model }, r.usage.costUsd)
        return r
    }
}
