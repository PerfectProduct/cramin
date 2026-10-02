package pro.perfectproduct.cramin.pipeline

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import pro.perfectproduct.cramin.data.db.CardStatus
import pro.perfectproduct.cramin.data.repo.*
import pro.perfectproduct.cramin.llm.*
import pro.perfectproduct.cramin.testing.FakeLlmClient
import pro.perfectproduct.cramin.testing.TestPipeline
import pro.perfectproduct.cramin.util.Lang

@RunWith(RobolectricTestRunner::class)
class MeaningStudyTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun legacyLemmaOnlySnapshotDoesNotAssignKnownToANewMeaning() = runTest {
        TestPipeline(tmp.root,FakeLlmClient()).use { p ->
            val id=p.documents.create(NewDocument.Text("The bank near the river opened today.","Synthetic",Lang.RU,Lang.EN))
            p.db.reprocessDao().put(pro.perfectproduct.cramin.data.db.ReprocessState(id,
                """[{"lemmaKey":"bank|NOUN","status":"KNOWN","starred":true}]""",true))
            assertTrue(p.processor().process(id) is ProcessOutcome.Ready)
            assertEquals(CardStatus.NEW,p.cards.deckCards(id,DeckFilter.ALL).single { it.lemma=="bank" }.status)
            assertTrue(requireNotNull(p.documents.get(id)).studyNotice)
            val retained=requireNotNull(p.db.reprocessDao().get(id))
            assertFalse(retained.pending)
            assertTrue(retained.snapshotJson.contains("KNOWN"))
        }
    }

    @Test fun sixteenMentionsBecomeThreeIndependentCardsAndSurviveReprocess() = runTest {
        val fake = FakeLlmClient()
        fake.interceptor = { request, _ ->
            val content = when(request.role) {
                ModelRole.EXTRACT -> Json.encodeToString(ExtractResponse(Messages.parsePairs(request.user).flatMap { pair -> pair.sentences.map { sentence ->
                    val g = when { sentence.idx < 10 -> listOf("банк","банка","банку","финансовое учреждение")[sentence.idx % 4]; sentence.idx < 14 -> "берег"; else -> "крен" }
                    ExtractedUnit(sentence.idx,"bank","bank",null,"NOUN",g,"tr_bank")
                } }))
                ModelRole.CONSOLIDATE -> {
                    val input = Json.decodeFromString<List<ConsolidateItemInput>>(request.user.substringAfter("ITEMS:\n"))
                    Json.encodeToString(ConsolidateResponse(input.map { item -> ConsolidatedItem(item.k,
                        item.o.groupBy { if (it.g in setOf("банк","банка","банку","финансовое учреждение")) "банк" else it.g }
                            .map { (g, occurrences) -> ConsolidatedSense(g,occurrences.map { it.id }) }) }))
                }
                else -> null
            }
            content?.let { LlmResponse(it,"stop",LlmUsage.ZERO,request.model) }
        }
        TestPipeline(tmp.root,fake).use { p ->
            val text = (0..15).joinToString("\n\n") { "The bank appears in context number $it today." }
            val id = p.documents.create(NewDocument.Text(text,"Synthetic",Lang.RU,Lang.EN))
            assertTrue(p.processor().process(id) is ProcessOutcome.Ready)
            val cards = p.cards.deckCards(id,DeckFilter.ALL)
            assertEquals(3,cards.size)
            assertEquals(setOf("банк","берег","крен"),cards.map { it.senses.single().translation }.toSet())
            assertEquals(16,p.db.cardDao().countOccurrences(id))
            for(c in cards) assertNotNull(c.senses.single().example)
            val bank=cards.single { it.meaningKey=="банк" }
            val shore=cards.single { it.meaningKey=="берег" }
            p.cards.setStatus(bank.id,CardStatus.KNOWN);p.cards.setStarred(bank.id,true)
            p.cards.setStatus(shore.id,CardStatus.LEARNING)
            p.documents.prepareReprocess(id)
            assertTrue(p.processor().process(id) is ProcessOutcome.Ready)
            val restored=p.cards.deckCards(id,DeckFilter.ALL).associateBy { it.meaningKey }
            assertEquals(CardStatus.KNOWN,restored.getValue("банк").status)
            assertTrue(restored.getValue("банк").starred)
            assertEquals(CardStatus.LEARNING,restored.getValue("берег").status)
            assertFalse(restored.getValue("берег").starred)
            assertEquals(CardStatus.NEW,restored.getValue("крен").status)
        }
    }
}
