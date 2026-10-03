package pro.perfectproduct.cramin.pipeline

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
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
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic Payment exceptions; never contacts a provider or uses the owner's text. */
@RunWith(RobolectricTestRunner::class)
class PaymentResumeTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun paymentDuringTranslationResumesToConsolidation() = resume(ModelRole.TRANSLATE)
    @Test fun paymentDuringParallelExtractionResumesToConsolidation() = resume(ModelRole.EXTRACT)
    @Test fun paymentDuringConsolidationResumesToCachedRecovery() = resume(ModelRole.CONSOLIDATE)

    private fun resume(role: ModelRole) = runTest {
        val fake = FakeLlmClient()
        val calls = AtomicInteger()
        fake.errorInjector = { req, _ ->
            if (req.role == role && calls.incrementAndGet() == if (role == ModelRole.CONSOLIDATE) 1 else 2)
                LlmException.Payment() else null
        }
        var failWrite = false
        TestPipeline(tmp.newFolder(), fake, checkpoint = {
            if (failWrite && it == "replacementDeleted") error("synthetic local write failure")
        }).use { p ->
            val id = p.documents.create(NewDocument.Text(Fixtures.text(Lang.EN), null, Lang.RU, Lang.EN))
            assertEquals(ErrorCode.PAYMENT, (p.processor().process(id) as ProcessOutcome.Failed).code)
            val savedJobs = p.db.jobDao().getByDocument(id).filter { it.status == JobStatus.DONE }
            assertTrue(savedJobs.isNotEmpty())
            val savedSegments = p.db.segmentDao().getByDocument(id)
            val before = fake.requests.size
            // Incomplete durable data must never be completed implicitly by a paid request.
            p.documents.requeue(id)
            assertTrue(p.processor().process(id, localConsolidationOnly = true) is ProcessOutcome.Failed)
            assertEquals(before, fake.requests.size)
            assertEquals(0, FailureDiagnostic.forDocument(p.documents.get(id)!!)!!.local!!.clientInvocations)
            assertEquals(savedSegments, p.db.segmentDao().getByDocument(id))
            for (job in savedJobs) assertEquals(job, p.db.jobDao().getById(job.id))

            fake.errorInjector = null // simulated balance restoration, no real account involved
            failWrite = true // separate local fault: explicitly NOT caused by the earlier Payment
            p.documents.requeue(id)
            assertEquals(ErrorCode.UNKNOWN, (p.processor().process(id) as ProcessOutcome.Failed).code)
            val diagnostic = FailureDiagnostic.forDocument(p.documents.get(id)!!)!!
            assertEquals(FailureStage.CONSOLIDATING, diagnostic.stage)
            assertEquals(ConsolidationStep.REPLACE_CARDS, diagnostic.local!!.step)
            for (job in savedJobs) assertEquals("DONE job reused", job, p.db.jobDao().getById(job.id))
            assertTrue(p.db.jobDao().getByDocument(id).all { it.status == JobStatus.DONE })
            val completeCalls = fake.requests.size
            failWrite = false
            fake.errorInjector = { _, _ -> AssertionError("No API permitted in cached recovery") }
            p.documents.requeue(id)
            assertTrue(p.processor().process(id, localConsolidationOnly = true) is ProcessOutcome.Ready)
            assertEquals(completeCalls, fake.requests.size)
            checkStructure(p, id)
        }
    }

    @Test fun paymentAfterFirstTranslationHalfDoesNotPublishPartialSegments() = partial(ModelRole.TRANSLATE)
    @Test fun paymentAfterFirstExtractionHalfDoesNotPublishPartialUnits() = partial(ModelRole.EXTRACT)

    private fun partial(role: ModelRole) = runTest {
        val fake = FakeLlmClient()
        var n = 0
        fake.interceptor = { req, _ -> if (req.role == role) {
            when (++n) {
                1 -> LlmResponse("{}", "length", LlmUsage(1, 1, 0.0001), req.model)
                3 -> throw LlmException.Payment()
                else -> null
            }
        } else null }
        val config = TestPipeline.FAKE_CONFIG.copy(pipeline = TestPipeline.FAKE_CONFIG.pipeline!!.copy(extractConcurrency = 1))
        TestPipeline(tmp.newFolder(), fake, config = config).use { p ->
            val id = p.documents.create(NewDocument.Text(Fixtures.text(Lang.EN), null, Lang.RU, Lang.EN))
            assertEquals(ErrorCode.PAYMENT, (p.processor().process(id) as ProcessOutcome.Failed).code)
            val kind = if (role == ModelRole.TRANSLATE) JobKind.TRANSLATE else JobKind.EXTRACT
            val partial = p.db.jobDao().getByKind(id, kind).first()
            assertNotEquals(JobStatus.DONE, partial.status)
            assertNull(partial.responseJson)
            assertEquals(2, partial.attempts) // length response + completed half, usage persisted
            if (role == ModelRole.TRANSLATE) assertTrue(p.db.segmentDao().getByDocument(id).isEmpty())
            assertTrue(p.db.cardDao().getByDocument(id).isEmpty())
            fake.interceptor = null
            p.documents.requeue(id)
            assertTrue(p.processor().process(id) is ProcessOutcome.Ready)
            assertEquals(3, p.db.jobDao().getById(partial.id)!!.attempts) // unfinished whole range requested again
            checkStructure(p, id)
        }
    }

    private suspend fun checkStructure(p: TestPipeline, id: Long) {
        val sentences = p.db.sentenceDao().getByDocument(id)
        val segments = p.db.segmentDao().getByDocument(id)
        assertTrue(sentences.all { it.segmentId != null })
        for (s in sentences) assertEquals(1, segments.count { s.idx in it.firstSentenceIdx..it.lastSentenceIdx })
        val jobs = p.db.jobDao().getByDocument(id)
        assertEquals(jobs.size, jobs.map { it.kind to it.idx }.distinct().size)
        val units = jobs.filter { it.kind == JobKind.EXTRACT }.flatMap {
            assertEquals(JobStatus.DONE, it.status)
            LlmJson.strict.decodeFromString<StoredExtraction>(it.responseJson!!).u
        }
        // Raw model output may repeat a token already within a sentence; compare to an uninterrupted run.
        TestPipeline(tmp.newFolder(), FakeLlmClient()).use { baseline ->
            val cleanId = baseline.documents.create(NewDocument.Text(Fixtures.text(Lang.EN), null, Lang.RU, Lang.EN))
            assertTrue(baseline.processor().process(cleanId) is ProcessOutcome.Ready)
            val cleanUnits = baseline.db.jobDao().getByKind(cleanId, JobKind.EXTRACT).flatMap {
                LlmJson.strict.decodeFromString<StoredExtraction>(it.responseJson!!).u
            }
            assertEquals(cleanUnits, units)
        }
        val occurrences = p.cards.observeOccurrences(id).first().map { it.occurrence }
        assertEquals(occurrences.size, occurrences.map { listOf(it.cardId, it.sentenceId, it.start, it.end) }.distinct().size)
        val cards = p.db.cardDao().getByDocument(id)
        assertEquals(cards.size, cards.map { it.lemmaKey to it.meaningKey }.distinct().size)
        assertEquals(2, cards.count { it.lemma == "bank" })
    }
}
