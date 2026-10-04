package pro.perfectproduct.cramin.pipeline

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import pro.perfectproduct.cramin.data.db.*
import pro.perfectproduct.cramin.data.repo.*
import pro.perfectproduct.cramin.llm.*
import pro.perfectproduct.cramin.testing.*
import pro.perfectproduct.cramin.util.Lang

@RunWith(RobolectricTestRunner::class)
class TopicPipelineTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun text() = NewDocument.Text("The bank accepts money. The river bank floods. Workers compare results today.", "Bank", Lang.RU, Lang.EN)

    @Test fun sameModelIntegratesCategoriesIntoFinalSensesAndClassifiesSinglesSeparately() = runTest {
        val fake = FakeLlmClient()
        val config = TestPipeline.FAKE_CONFIG.let { it.copy(roles = it.roles + ("consolidate" to it.roles.getValue("topic"))) }
        TestPipeline(tmp.root, fake, config).use { p ->
            val id = p.documents.create(text())
            assertTrue(p.processor().process(id) is ProcessOutcome.Ready)
            val bank = p.db.cardDao().getByDocument(id).filter { it.lemma == "bank" }
            assertEquals(2, bank.size); assertTrue(bank.all { it.category == TopicCategory.RELATED })
            val request = fake.requests.single { it.role == ModelRole.CONSOLIDATE }
            assertEquals(TopicCategories.consolidationSchema, request.schema)
            assertTrue(request.strictTopic)
            val separate = fake.requests.filter { it.role == ModelRole.TOPIC }.flatMap { Json.parseToJsonElement(it.user).jsonObject.getValue("items").jsonArray }
            assertTrue(separate.none { it.jsonObject.getValue("lemma").jsonPrimitive.content == "bank" })
            val calls = fake.requests.size
            p.documents.requeue(id)
            assertTrue(p.processor().process(id, localConsolidationOnly = true) is ProcessOutcome.Ready)
            assertEquals(calls, fake.requests.size)
            assertTrue(p.db.cardDao().getByDocument(id).all { it.category != null })
        }
    }

    @Test fun newCardsFallbackAndAllFiltersAreOfflineAndPreserveProgress() = runTest {
        val fake = FakeLlmClient()
        fake.interceptor = { r, _ -> if (r.role == ModelRole.CONSOLIDATE) LlmResponse("invalid", "stop", LlmUsage.ZERO, r.model) else null }
        TestPipeline(tmp.root, fake).use { p ->
            val id = p.documents.create(text())
            assertTrue(p.processor().process(id) is ProcessOutcome.Ready)
            val cards = p.db.cardDao().getByDocument(id)
            assertTrue(cards.isNotEmpty()); assertTrue(cards.all { it.category != null })
            val bank = cards.filter { it.lemma == "bank" }; assertEquals(2, bank.size)
            cards.forEachIndexed { index, c -> p.db.cardDao().setCategory(id, c.id, TopicCategory.entries[index % 3]) }
            p.cards.setStatus(cards[0].id, CardStatus.KNOWN); p.cards.setStarred(cards[1].id, true)
            val before = p.db.cardDao().getByDocument(id)
            val calls = fake.requests.size
            for (mask in 0..7) for (f in DeckFilter.entries) {
                p.db.documentDao().setCategoryMask(id, mask)
                assertEquals(before.filter { CategoryFilter.accepts(it, mask, f) }.map { it.id }, p.cards.deckCards(id, f).map { it.id })
            }
            assertEquals(calls, fake.requests.size)
            assertEquals(before, p.db.cardDao().getByDocument(id))
            p.db.documentDao().setCategoryMask(id, 7)
            kotlinx.coroutines.coroutineScope {
                listOf(1, 2, 4).map { bit -> async { p.db.documentDao().toggleCategory(id, bit) } }.forEach { it.await() }
            }
            assertEquals(0, p.documents.get(id)?.categoryMask)
        }
    }

    @Test fun enrichmentKeepsIdsAndPartialResultsAcrossRestartAndFailureKeepsReady() = runTest {
        val fake = FakeLlmClient()
        TestPipeline(tmp.root, fake).use { p ->
            val id = p.documents.create(text()); assertTrue(p.processor().process(id) is ProcessOutcome.Ready)
            val before = p.db.cardDao().getByDocument(id)
            // Simulate installed v4 cards without altering any learning data.
            p.db.openHelper.writableDatabase.execSQL("UPDATE Card SET category = NULL WHERE documentId = ?", arrayOf(id))
            p.db.documentDao().setTopicSnapshot(id, null)
            var calls = 0
            fake.interceptor = { r, _ -> if (r.role == ModelRole.TOPIC) {
                calls++
                if (calls == 1) LlmResponse("{}", "length", LlmUsage.ZERO, r.model)
                else if (calls == 3) throw LlmException.Network("offline") else null
            } else null }
            assertTrue(p.processor().enrichCategories(id) is ProcessOutcome.Failed)
            val partial = p.db.cardDao().getByDocument(id)
            assertTrue(partial.any { it.category != null }); assertTrue(partial.any { it.category == null })
            assertEquals(DocStatus.READY, p.documents.get(id)?.status)
            assertEquals(before.map { it.id }, p.cards.deckCards(id, DeckFilter.ALL).map { it.id })
            val already = partial.filter { it.category != null }.map { it.id.toString() }.toSet()
            val start = fake.requests.size; fake.interceptor = null
            assertTrue(p.processor().enrichCategories(id) is ProcessOutcome.Ready)
            val after = p.db.cardDao().getByDocument(id)
            assertEquals(before, after)
            val requested = fake.requests.drop(start).flatMap { Json.parseToJsonElement(it.user).jsonObject.getValue("items").jsonArray.map { v -> v.jsonObject.getValue("id").jsonPrimitive.content } }
            assertTrue(requested.none { it in already })
            assertTrue(fake.requests.drop(start).all { it.role == ModelRole.TOPIC })
        }
    }

    @Test fun networkFailureBeforeFirstPartCannotLeavePreviousJobDone() = runTest {
        val fake = FakeLlmClient()
        TestPipeline(tmp.root, fake).use { p ->
            val id = p.documents.create(text()); assertTrue(p.processor().process(id) is ProcessOutcome.Ready)
            p.db.openHelper.writableDatabase.execSQL("UPDATE Card SET category = NULL WHERE documentId = ?", arrayOf(id))
            assertEquals(JobStatus.DONE, p.db.jobDao().get(id, JobKind.TOPIC, 0)?.status)
            fake.errorInjector = { request, _ -> if (request.role == ModelRole.TOPIC) LlmException.Network("offline") else null }
            assertTrue(p.processor().enrichCategories(id) is ProcessOutcome.Failed)
            assertEquals(JobStatus.PENDING, p.db.jobDao().get(id, JobKind.TOPIC, 0)?.status)
            assertEquals(DocStatus.READY, p.documents.get(id)?.status)
            assertTrue(p.db.cardDao().getByDocument(id).all { it.category == null })
        }
    }

    @Test fun sharedDeckFiltersSourcesBeforeMergingAndUndoTouchesOnlySelectedSources() = runTest {
        TestPipeline(tmp.root, FakeLlmClient()).use { p ->
            val a = p.documents.create(text()); val b = p.documents.create(text())
            p.processor().process(a); p.processor().process(b)
            val ca = p.db.cardDao().getByDocument(a); val cb = p.db.cardDao().getByDocument(b)
            ca.forEach { p.db.cardDao().setCategory(a, it.id, TopicCategory.CORE) }
            cb.forEach { p.db.cardDao().setCategory(b, it.id, TopicCategory.GENERAL) }
            val selected = p.cards.sharedDeckCards(Lang.EN, Lang.RU, categoryMask = 1)
            assertTrue(selected.all { it.documentId == a && it.duplicateIds.isEmpty() })
            val c = selected.first(); val hidden = cb.first { it.lemmaKey == c.lemmaKey && it.meaningKey == c.meaningKey }
            val state = pro.perfectproduct.cramin.study.SessionState("all:en-ru:1", listOf(c.id), sourceGroups = mapOf(c.id to listOf(c.id)))
            var saved = state
            val machine = pro.perfectproduct.cramin.study.StudySessionMachine(state, p.cards.statusStore(true) { c }, { saved = it })
            machine.dispatch(pro.perfectproduct.cramin.study.SessionEvent.SwipeRight)
            assertEquals(CardStatus.NEW, p.cards.getStatus(hidden.id))
            val restored = requireNotNull(pro.perfectproduct.cramin.study.SessionState.fromJson(saved.toJson()))
            val resumed = pro.perfectproduct.cramin.study.StudySessionMachine(restored, p.cards.statusStore(true) { c }, {})
            resumed.dispatch(pro.perfectproduct.cramin.study.SessionEvent.Undo)
            assertEquals(CardStatus.NEW, p.cards.getStatus(c.id)); assertEquals(CardStatus.NEW, p.cards.getStatus(hidden.id))
            assertEquals(state.sourceGroups, resumed.state.value.sourceGroups)
        }
    }
}
