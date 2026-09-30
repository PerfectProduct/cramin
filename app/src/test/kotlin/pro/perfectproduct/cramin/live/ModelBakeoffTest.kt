package pro.perfectproduct.cramin.live

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import pro.perfectproduct.cramin.data.db.JobKind
import pro.perfectproduct.cramin.data.repo.DeckFilter
import pro.perfectproduct.cramin.data.repo.NewDocument
import pro.perfectproduct.cramin.llm.ModelsConfigFile
import pro.perfectproduct.cramin.llm.PipelineParamsJson
import pro.perfectproduct.cramin.llm.RoleConfig
import pro.perfectproduct.cramin.pipeline.ProcessOutcome
import pro.perfectproduct.cramin.testing.Fixtures
import pro.perfectproduct.cramin.testing.TestPipeline
import pro.perfectproduct.cramin.util.Lang
import pro.perfectproduct.cramin.util.Log
import java.io.File

/**
 * Бейкофф моделей перевода (фаза 3): живой пайплайн на трёх фикстурах с двумя кандидатами `translate`.
 * Переводы и таблица сохраняются в docs/model-bakeoff/. Запуск: ./gradlew testDebugUnitTest -Plive=true -Pbakeoff=true
 */
@RunWith(RobolectricTestRunner::class)
class ModelBakeoffTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val low = Json.parseToJsonElement("""{"effort":"low"}""").jsonObject

    /** Общие роли для всех кандидатов; варьируется только translate. */
    private fun config(translate: String) = ModelsConfigFile(
        schemaVersion = 1,
        roles = mapOf(
            "brief" to RoleConfig("google/gemini-3.1-flash-lite", 0.2, 6000, low),
            "translate" to RoleConfig(translate, 0.3, null, low),
            "extract" to RoleConfig("google/gemini-3.1-flash-lite", 0.1, 8000, low),
            "consolidate" to RoleConfig("google/gemini-3.1-flash-lite", 0.1, 4000, low),
            "stt" to RoleConfig("placeholder/stt"),
        ),
        pipeline = PipelineParamsJson(60_000, 4000, 700, 3, mapOf("en" to 1.4, "ru" to 2.6, "he" to 2.6)),
    )

    private val candidates = listOf(
        "strong" to "google/gemini-3.8-flash",
        "cheap" to "openai/gpt-5.6-luna",
    )
    private val pairs = listOf(Lang.EN to Lang.RU, Lang.RU to Lang.EN, Lang.HE to Lang.RU, Lang.EN to Lang.HE)

    @Before
    fun setUp() {
        LiveEnv.requireKey()
        assumeTrue("бейкофф не запрошен (-Pbakeoff=true)", LiveEnv.bakeoffRequested)
        Log.install { level, tag, message, _ -> if (level >= Log.Level.WARN) println("[$level] $tag: $message") }
        runBlocking { LiveEnv.catalog.get() }
    }

    data class Row(val candidate: String, val model: String, val pair: String, val seconds: Long, val promptTokens: Int, val completionTokens: Int, val translateCost: Double, val totalCost: Double, val sections: Int, val cards: Int)

    @Test
    fun bakeoff() = runBlocking {
        val outDir = File(LiveEnv.repoRoot, "docs/model-bakeoff").apply { mkdirs() }
        val rows = ArrayList<Row>()
        for ((slug, model) in candidates) {
            for ((src, tgt) in pairs) {
                TestPipeline(tmp.newFolder(), LiveEnv.client(), config = config(model), catalog = LiveEnv.catalog.cached()).use { p ->
                    val id = p.documents.create(NewDocument.Text(Fixtures.text(src), null, tgt, null))
                    val t0 = System.currentTimeMillis()
                    val outcome = p.processor().process(id)
                    val sec = (System.currentTimeMillis() - t0) / 1000
                    assertTrue("$slug $src→$tgt: $outcome", outcome is ProcessOutcome.Ready)
                    val doc = p.documents.get(id)!!
                    val jobs = p.db.jobDao().getByDocument(id)
                    val tJobs = jobs.filter { it.kind == JobKind.TRANSLATE }
                    val sentences = p.db.sentenceDao().getByDocument(id)
                    val segments = p.db.segmentDao().getByDocument(id)
                    val cards = p.cards.deckCards(id, DeckFilter.ALL)
                    rows += Row(slug, model, "${src.code}→${tgt.code}", sec, tJobs.sumOf { it.promptTokens }, tJobs.sumOf { it.completionTokens }, tJobs.sumOf { it.costUsd ?: 0.0 }, doc.costUsd ?: 0.0, tJobs.size, cards.size)
                    File(outDir, "$slug-${src.code}-${tgt.code}.md").writeText(
                        buildString {
                            appendLine("# ${src.code}→${tgt.code} — `$model` (translate), остальные роли: google/gemini-3.1-flash-lite")
                            appendLine()
                            appendLine("- секций: ${tJobs.size}, токены перевода: ${tJobs.sumOf { it.promptTokens }}/${tJobs.sumOf { it.completionTokens }}, стоимость перевода: \$${"%.5f".format(tJobs.sumOf { it.costUsd ?: 0.0 })}, весь документ: \$${"%.5f".format(doc.costUsd ?: 0.0)}, время: ${sec} с")
                            appendLine("- бриф: ${doc.title} ${doc.emoji}; карточек: ${cards.size}")
                            appendLine()
                            appendLine("## Параллельный текст")
                            appendLine()
                            for (s in segments) {
                                appendLine("**[${s.firstSentenceIdx}-${s.lastSentenceIdx}]** " + (s.firstSentenceIdx..s.lastSentenceIdx).joinToString(" ") { i -> sentences.first { it.idx == i }.text })
                                appendLine()
                                appendLine("→ ${s.translation}")
                                appendLine()
                            }
                            appendLine("## Карточки (первые 40)")
                            appendLine()
                            for (c in cards.take(40)) appendLine("- **${c.lemma}**${c.lemmaVocalized?.let { " ($it)" } ?: ""} `${c.pos}` — " + c.senses.joinToString("; ") { it.translation })
                        },
                    )
                    println("BAKEOFF $slug ${src.code}→${tgt.code}: ${sec}s translate=\$${"%.5f".format(tJobs.sumOf { it.costUsd ?: 0.0 })} total=\$${"%.5f".format(doc.costUsd ?: 0.0)} cards=${cards.size} spent=${"%.4f".format(LiveBudget.spentUsd)}")
                }
            }
        }
        File(outDir, "results.md").writeText(
            buildString {
                appendLine("# Результаты бейкоффа (автоматически, ${java.time.LocalDate.now()})")
                appendLine()
                appendLine("| кандидат | модель | пара | секций | токены перевода in/out | стоимость перевода | весь документ | время | карточек |")
                appendLine("|---|---|---|---:|---:|---:|---:|---:|---:|")
                for (r in rows) appendLine("| ${r.candidate} | ${r.model} | ${r.pair} | ${r.sections} | ${r.promptTokens}/${r.completionTokens} | \$${"%.5f".format(r.translateCost)} | \$${"%.5f".format(r.totalCost)} | ${r.seconds} с | ${r.cards} |")
                appendLine()
                for ((slug, model) in candidates) {
                    val c = rows.filter { it.candidate == slug }
                    appendLine("- **$slug** ($model): перевод всего \$${"%.5f".format(c.sumOf { it.translateCost })}, документы всего \$${"%.5f".format(c.sumOf { it.totalCost })}, время ${c.sumOf { it.seconds }} с")
                }
                appendLine()
                appendLine("Суммарная стоимость бейкоффа: \$${"%.5f".format(rows.sumOf { it.totalCost })}")
            },
        )
    }
}
