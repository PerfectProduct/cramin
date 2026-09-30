package pro.perfectproduct.cramin.live

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pro.perfectproduct.cramin.llm.KeyCheck
import pro.perfectproduct.cramin.llm.KeyChecker
import pro.perfectproduct.cramin.llm.ModelConfigResolver
import pro.perfectproduct.cramin.llm.ModelRole
import pro.perfectproduct.cramin.util.Log
import java.io.File

/**
 * Живой каталог (фаза 3): ключ валиден, модели из config/models.json существуют и поддерживают
 * structured outputs; сводка кандидатов пишется в build/reports/live/model-candidates.md.
 */
class LiveCatalogTest {
    @Before
    fun silence() = Log.install { _, _, _, _ -> }

    @Test
    fun keyIsValid() = runBlocking {
        val key = LiveEnv.requireKey()
        val result = KeyChecker(OkHttpClient()).check(key)
        assertTrue("ключ отвергнут: $result", result is KeyCheck.Valid)
    }

    @Test
    fun configuredModelsExistWithStructuredOutputs() = runBlocking {
        LiveEnv.requireKey()
        val snapshot = LiveEnv.catalog.get(force = true)
        assertNotNull("каталог недоступен", snapshot)
        val effective = ModelConfigResolver.resolve(null, null, LiveEnv.repoConfig, snapshot)
        for (role in ModelRole.entries) {
            val r = effective.role(role)
            if (role.isText) {
                val m = snapshot!!.find(r.model)
                assertNotNull("модель ${r.model} (${role.key}) отсутствует в каталоге", m)
                assertTrue("модель ${r.model} без structured outputs", m!!.supportsStructuredOutputs)
            }
        }
        assertTrue("предупреждения конфига: ${effective.warnings}", effective.warnings.isEmpty())

        val report = File(LiveEnv.reportDir, "model-candidates.md")
        val so = snapshot!!.structuredOutputModels().filter { (it.promptPricePerMillion ?: 0.0) >= 0 }
            .sortedBy { (it.promptPricePerMillion ?: 0.0) + (it.completionPricePerMillion ?: 0.0) }
        report.writeText(
            buildString {
                appendLine("# Кандидаты по живому каталогу (${snapshot.models.size} моделей, structured outputs: ${so.size})")
                appendLine()
                appendLine("| id | in \$/M | out \$/M | ctx | max_out |")
                appendLine("|---|---:|---:|---:|---:|")
                for (m in so.take(120)) appendLine("| ${m.id} | ${"%.3f".format(m.promptPricePerMillion)} | ${"%.3f".format(m.completionPricePerMillion)} | ${m.contextLength} | ${m.maxCompletionTokens} |")
            },
        )
    }
}
