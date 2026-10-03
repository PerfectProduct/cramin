package pro.perfectproduct.cramin.pipeline

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import pro.perfectproduct.cramin.data.db.*
import pro.perfectproduct.cramin.data.repo.NewDocument
import pro.perfectproduct.cramin.llm.*
import pro.perfectproduct.cramin.testing.*
import pro.perfectproduct.cramin.util.Lang

@RunWith(RobolectricTestRunner::class)
class LengthContinuationTest {
    @get:Rule val tmp = TemporaryFolder()
    private val words = listOf("bank", "spring", "light", "match")
    private fun fake() = FakeLlmClient(polysemy = words.associateWith { FakeLlmClient.Polysemy(listOf("river"), "значение один", "значение два") })
    private val source get() = words.joinToString(" ") { "The $it beside the river. The $it accepts money." }

    @Test fun legacyLengthAfterOneOrThreeResponsesDoesNotResendOriginalBatch() = runTest {
        for (attempts in listOf(1, 3)) {
            val fake = fake()
            TestPipeline(tmp.newFolder(), fake).use { p ->
                val id = p.documents.create(NewDocument.Text(source, null, Lang.RU, Lang.EN))
                assertTrue(p.processor().process(id) is ProcessOutcome.Ready)
                val job = p.db.jobDao().getByKind(id, JobKind.CONSOLIDATE).single()
                p.db.jobDao().update(job.copy(status = JobStatus.PENDING, responseJson = null, finishReason = "length", attempts = attempts))
                val done = p.db.jobDao().getByDocument(id).filter { it.kind != JobKind.CONSOLIDATE }
                val before = fake.requests.size
                p.documents.requeue(id)
                assertTrue(p.processor().process(id) is ProcessOutcome.Ready)
                val requests = fake.requests.drop(before)
                assertEquals(2, requests.size)
                assertTrue(requests.all { it.role == ModelRole.CONSOLIDATE })
                assertTrue(requests.all { LlmJson.parse<List<ConsolidateItemInput>>(it.user.substringAfter("ITEMS:\n")).size == 2 })
                for (old in done) assertEquals(old, p.db.jobDao().getById(old.id))
                assertEquals(8, p.db.cardDao().getByDocument(id).count { it.lemma in words })
                assertEquals(1, requests.map { it.processingAttemptId }.distinct().size)
                assertEquals(requests.size, requests.map { it.logicalRequestId }.distinct().size)
                assertEquals(setOf("L", "R"), requests.map { it.partPath }.toSet())
                val summary = DocumentStateSummary.copyText(p.db, id, p.deps.stoplists, "test", 34)
                assertTrue(summary.contains("MATCHES_SAVED_PIPELINE_RESULTS"))
            }
        }
    }
    @Test fun completedChildSurvivesNewLengthThenPaymentAndFreshProcessor() = runTest {
        val fake = fake()
        TestPipeline(tmp.newFolder(), fake).use { p ->
            val id = p.documents.create(NewDocument.Text(source, null, Lang.RU, Lang.EN))
            p.processor().process(id)
            val job = p.db.jobDao().getByKind(id, JobKind.CONSOLIDATE).single()
            p.db.jobDao().update(job.copy(status = JobStatus.PENDING, responseJson = null, finishReason = "length", attempts = 3))
            // Addressed recovery must skip even an old FAILED brief fallback.
            val brief = p.db.jobDao().getByKind(id, JobKind.BRIEF).single()
            p.db.jobDao().update(brief.copy(status = JobStatus.FAILED))
            val saved = p.db.jobDao().getByDocument(id).filter { it.kind != JobKind.CONSOLIDATE }
            var n = 0
            val seen = mutableListOf<List<String>>()
            fake.interceptor = { req, _ ->
                assertEquals(ModelRole.CONSOLIDATE, req.role)
                val items = LlmJson.parse<List<ConsolidateItemInput>>(req.user.substringAfter("ITEMS:\n"))
                seen += items.map { it.k }
                when (++n) {
                    2 -> LlmResponse("{}", "length", LlmUsage(1, 1, 0.001), req.model)
                    4 -> throw LlmException.Payment()
                    else -> null
                }
            }
            p.documents.requeue(id)
            assertEquals(ErrorCode.PAYMENT, (p.processor().process(id, consolidationOnly = true) as ProcessOutcome.Failed).code)
            assertEquals(listOf(2, 2, 1, 1), seen.map { it.size })
            val progress = ConsolidationParts.decode(p.db.jobDao().getById(job.id)!!.responseJson)!!
            assertEquals(2, progress.done.size)
            val before = fake.requests.size
            p.documents.requeue(id)
            assertEquals(ErrorCode.CONSOLIDATION_CACHE_UNFINISHED,
                (p.processor().process(id, localConsolidationOnly = true) as ProcessOutcome.Failed).code)
            assertEquals(before, fake.requests.size)
            fake.interceptor = null
            p.documents.requeue(id)
            assertTrue(p.processor().process(id, consolidationOnly = true) is ProcessOutcome.Ready)
            assertEquals(1, fake.requests.size - before)
            for (old in saved) assertEquals(old, p.db.jobDao().getById(old.id))
            assertEquals(8, p.db.cardDao().getByDocument(id).count { it.lemma in words })
            assertEquals(1, p.db.jobDao().getByKind(id, JobKind.CONSOLIDATE).size)
        }
    }

    @Test fun committedChildSurvivesInjectedExceptionBeforeNextCall() = runTest {
        val fake = fake()
        var armed = false
        var id = 0L
        var pipeline: TestPipeline? = null
        TestPipeline(tmp.newFolder(), fake, checkpoint = { point ->
            if (armed && point == "consolidationPartSaved") {
                val json = kotlinx.coroutines.runBlocking { pipeline!!.db.jobDao().getByKind(id, JobKind.CONSOLIDATE).single().responseJson }
                if (ConsolidationParts.decode(json)?.done?.isNotEmpty() == true) { armed = false; error("injected after durable child") }
            }
        }).use { p ->
            pipeline = p
            id = p.documents.create(NewDocument.Text(source, null, Lang.RU, Lang.EN))
            p.processor().process(id)
            val job = p.db.jobDao().getByKind(id, JobKind.CONSOLIDATE).single()
            p.db.jobDao().update(job.copy(status = JobStatus.PENDING, responseJson = null, finishReason = "length"))
            p.documents.requeue(id)
            armed = true
            assertTrue(p.processor().process(id, consolidationOnly = true) is ProcessOutcome.Failed)
            assertEquals(1, ConsolidationParts.decode(p.db.jobDao().getById(job.id)!!.responseJson)!!.done.size)
            val before = fake.requests.size
            p.documents.requeue(id)
            assertTrue(p.processor().process(id, consolidationOnly = true) is ProcessOutcome.Ready)
            assertEquals(1, fake.requests.size - before)
        }
    }

    @Test fun budgetsCountSerializedInputAndNeverIncreaseExplicitOutput() {
        val request = LlmRequest(ModelRole.CONSOLIDATE, "fake/model", "system", "русский ".repeat(1200),
            Schemas.CONSOLIDATE_NAME, Schemas.CONSOLIDATE, 0.1, 4000)
        val model = CatalogModel("fake/model", "fake", 12000, 5000, null, null,
            listOf("structured_outputs", "temperature"), listOf("text"), listOf("text"))
        val budget = ConsolidationBudget.evaluate(request, model)
        assertEquals(4000, budget.output)
        assertTrue(budget.inputBytes > request.user.length)
        assertFalse(budget.fits)
        assertTrue(ConsolidationBudget.evaluate(request.copy(user = "коротко"), model).fits)
        assertTrue(runCatching { ConsolidationBudget.evaluate(request.copy(maxTokens = 6000), model) }.exceptionOrNull() is ConsolidationLimit)
    }

}
