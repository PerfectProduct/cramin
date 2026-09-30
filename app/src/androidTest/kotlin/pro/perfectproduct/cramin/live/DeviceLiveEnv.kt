package pro.perfectproduct.cramin.live

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import pro.perfectproduct.cramin.llm.LlmClient
import pro.perfectproduct.cramin.llm.LlmException
import pro.perfectproduct.cramin.llm.LlmRequest
import pro.perfectproduct.cramin.llm.LlmResponse
import pro.perfectproduct.cramin.util.Log
import java.io.File

/**
 * Живые инструментированные тесты (SPEC §14.3а): ключ приходит аргументом инструментирования
 * `openrouterKey` из scripts/test-device.sh --live и в APK не вшивается. Стоимость пишется в
 * `additionalTestOutputDir/live-cost.json` (Gradle забирает его в build/outputs) и в logcat (тег CraminLive).
 */
object DeviceLiveEnv {
    private val args get() = InstrumentationRegistry.getArguments()
    val apiKey: String? get() = args.getString("openrouterKey")?.trim()?.takeIf { it.isNotEmpty() }
    val budgetUsd: Double get() = args.getString("liveBudgetUsd")?.toDoubleOrNull() ?: 1.0

    fun requireKey(): String {
        assumeTrue("нет аргумента openrouterKey — живой тест пропущен", apiKey != null)
        return apiKey!!
    }

    @Volatile
    var spentUsd: Double = 0.0
        private set

    @Volatile
    var calls: Int = 0
        private set

    @Synchronized
    fun record(model: String, costUsd: Double?) {
        calls++
        spentUsd += costUsd ?: 0.0
        val line = """{"spentUsd":$spentUsd,"budgetUsd":$budgetUsd,"calls":$calls,"lastModel":"$model"}"""
        android.util.Log.i("CraminLive", "cost $line")
        args.getString("additionalTestOutputDir")?.let { dir ->
            runCatching { File(dir).apply { mkdirs() }.resolve("live-cost.json").writeText(line) }
        }
        if (spentUsd > budgetUsd) throw LlmException.BudgetExceeded(spentUsd, budgetUsd)
    }

    fun client(inner: LlmClient): LlmClient = object : LlmClient {
        override suspend fun complete(request: LlmRequest): LlmResponse {
            if (spentUsd > budgetUsd) throw LlmException.BudgetExceeded(spentUsd, budgetUsd)
            val r = inner.complete(request)
            record(r.model.ifEmpty { request.model }, r.usage.costUsd)
            return r
        }
    }

    fun quietLogs() = Log.install { level, tag, message, _ -> if (level >= Log.Level.INFO) android.util.Log.i("CraminLive/$tag", message) }
}
