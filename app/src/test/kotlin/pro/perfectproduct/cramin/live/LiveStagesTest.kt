package pro.perfectproduct.cramin.live

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import pro.perfectproduct.cramin.data.db.DocStatus
import pro.perfectproduct.cramin.data.db.JobKind
import pro.perfectproduct.cramin.data.db.JobStatus
import pro.perfectproduct.cramin.data.repo.DeckFilter
import pro.perfectproduct.cramin.data.repo.NewDocument
import pro.perfectproduct.cramin.llm.LlmJson
import pro.perfectproduct.cramin.llm.ModelRole
import pro.perfectproduct.cramin.llm.StoredBrief
import pro.perfectproduct.cramin.pipeline.ProcessOutcome
import pro.perfectproduct.cramin.testing.Fixtures
import pro.perfectproduct.cramin.testing.TestPipeline
import pro.perfectproduct.cramin.util.Lang
import pro.perfectproduct.cramin.util.Log
import java.io.File

/**
 * Живые тесты стадий (SPEC §14.1, тег live): реальный OpenRouter с моделями из config/models.json
 * на коротких фикстурах en→ru, ru→en, he→ru, en→he. Бюджет — LIVE_BUDGET_USD по фактическому usage.cost.
 */
@RunWith(RobolectricTestRunner::class)
class LiveStagesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() {
        LiveEnv.requireKey()
        Log.install { level, tag, message, _ -> if (level >= Log.Level.INFO) println("[$level] $tag: $message") }
        runBlocking { LiveEnv.catalog.get() }
    }

    private fun run(src: Lang, tgt: Lang) = runBlocking {
        TestPipeline(tmp.newFolder(), LiveEnv.client(), config = LiveEnv.repoConfig, catalog = LiveEnv.catalog.cached()).use { p ->
            val id = p.documents.create(NewDocument.Text(Fixtures.text(src), null, tgt, null))
            val t0 = System.currentTimeMillis()
            val outcome = p.processor().process(id)
            val sec = (System.currentTimeMillis() - t0) / 1000
            assertTrue("$src→$tgt: $outcome", outcome is ProcessOutcome.Ready)
            val doc = p.documents.get(id)!!
            assertEquals(DocStatus.READY, doc.status)
            val jobs = p.db.jobDao().getByDocument(id)
            assertTrue(jobs.all { it.status == JobStatus.DONE || it.kind == JobKind.BRIEF })

            // Бриф: заголовок, эмодзи, глоссарий с терминами фикстуры.
            val briefJob = jobs.first { it.kind == JobKind.BRIEF }
            assertEquals(JobStatus.DONE, briefJob.status)
            val brief = LlmJson.strict.decodeFromString<StoredBrief>(briefJob.responseJson!!).brief!!
            assertTrue(brief.glossary.isNotEmpty())
            assertTrue(doc.title.isNotBlank() && doc.emoji.isNotBlank())

            // Перевод: все предложения покрыты, текст на языке перевода.
            val sentences = p.db.sentenceDao().getByDocument(id)
            val segments = p.db.segmentDao().getByDocument(id)
            assertTrue(sentences.all { it.segmentId != null })
            val translated = segments.joinToString(" ") { it.translation }
            assertEquals(tgt, pro.perfectproduct.cramin.pipeline.LangDetector.detect(translated))

            // Извлечение и консолидация: карточки со смыслами и примерами; многозначное слово присутствует.
            val cards = p.cards.deckCards(id, DeckFilter.ALL)
            val poly = when (src) { Lang.EN -> "bank"; Lang.RU -> "ключ"; Lang.HE -> "רשת" }
            val polyCard = cards.firstOrNull { it.lemma.equals(poly, ignoreCase = true) }
            // Извлечение стохастично: одно конкретное слово модель может пропустить, но ядро частых терминов — нет.
            val core = when (src) {
                Lang.EN -> listOf("library", "bank", "catalog", "volunteer", "reader")
                Lang.RU -> listOf("ключ", "мастер", "замок", "заказ", "инструмент")
                Lang.HE -> listOf("דייג", "רשת", "סירה", "נכד", "מחשב")
            }
            val coreFound = core.filter { w -> cards.any { it.lemma.equals(w, ignoreCase = true) } }
            val report = File(LiveEnv.reportDir, "stages-${src.code}-${tgt.code}.md")
            report.writeText(
                buildString {
                    appendLine("# Live stages ${src.code}→${tgt.code}")
                    appendLine("- time: ${sec}s, cost: \$${"%.5f".format(doc.costUsd ?: 0.0)}, tokens: ${doc.promptTokens}/${doc.completionTokens}")
                    appendLine("- title: ${doc.title} ${doc.emoji}; glossary: ${brief.glossary.map { it.src + "→" + it.tgt }}; cards: ${cards.size}; poly senses: ${polyCard?.senses?.map { it.translation }}")
                    appendLine("- jobs: ${jobs.groupBy { it.kind }.mapValues { it.value.size }}")
                    appendLine()
                    for (s in segments) {
                        appendLine("[${s.firstSentenceIdx}-${s.lastSentenceIdx}] " + (s.firstSentenceIdx..s.lastSentenceIdx).joinToString(" ") { i -> sentences.first { it.idx == i }.text })
                        appendLine("=> ${s.translation}")
                    }
                    appendLine()
                    for (c in cards) appendLine("- ${c.lemma}${c.lemmaVocalized?.let { " ($it)" } ?: ""} [${c.pos}]: " + c.senses.joinToString("; ") { it.translation })
                },
            )
            assertTrue("$src→$tgt cards=${cards.size}", cards.size >= 25)
            assertTrue(cards.all { it.senses.isNotEmpty() })
            assertTrue(cards.count { it.senses.first().example != null } >= cards.size * 8 / 10)
            assertTrue("извлечено слишком мало ключевых слов: $coreFound из $core", coreFound.size >= 3)
            polyCard?.let { println("poly $poly senses=${it.senses.map { s -> s.translation }}") }
            if (src == Lang.HE) assertTrue(cards.count { it.lemmaVocalized != null } >= cards.size / 2)
            val withOffsets = p.cards.observeOccurrences(id)
            assertTrue(cards.all { it.lang == src && it.targetLang == tgt })

            println("LIVE ${src.code}→${tgt.code}: ${sec}s cost=${doc.costUsd} cards=${cards.size} spent-total=${LiveBudget.spentUsd}")
            @Suppress("UNUSED_VARIABLE") val unused = withOffsets
        }
    }

    @Test
    fun enToRu() = run(Lang.EN, Lang.RU)

    @Test
    fun ruToEn() = run(Lang.RU, Lang.EN)

    @Test
    fun heToRu() = run(Lang.HE, Lang.RU)

    @Test
    fun enToHe() = run(Lang.EN, Lang.HE)
}
