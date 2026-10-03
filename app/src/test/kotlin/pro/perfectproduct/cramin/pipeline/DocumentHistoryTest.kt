package pro.perfectproduct.cramin.pipeline

import kotlinx.coroutines.runBlocking
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
class DocumentHistoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private suspend fun TestPipeline.create() = documents.create(NewDocument.Text(
        "The bank beside the river. The bank accepts money.", null, Lang.RU, Lang.EN))
    private suspend fun TestPipeline.summary(id: Long) = DocumentStateSummary.copyText(db, id, deps.stoplists, "COPY_VERSION", 34)

    @Test fun transientClientEventThenSuccessIsHistoricalAndCorrelated() = runTest {
        val fake = FakeLlmClient()
        fake.interceptor = { req, _ ->
            if (req.role == ModelRole.CONSOLIDATE) runBlocking {
                req.onFailureDiagnostic?.invoke(RequestDiagnostic.capture(req, ChatRequestBody.build(req, null, true))
                    .copy(observation = "TRANSPORT_EXCEPTION_RESPONSE_NOT_RECORDED"))
            }
            null
        }
        TestPipeline(tmp.newFolder(), fake).use { p ->
            val id = p.create()
            assertTrue(p.processor().process(id) is ProcessOutcome.Ready)
            val doc = p.documents.get(id)!!
            assertNull(doc.errorCode)
            val event = FailureDiagnostic.forDocument(doc)!!
            assertEquals(DiagnosticOrigin.CLIENT_ATTEMPT_ISSUE, event.origin)
            assertNotNull(event.recoveredAtEpochMs)
            assertEquals(event.recoveryAttemptId, event.request!!.processingAttemptId)
            assertNotEquals(event.request!!.attemptId, event.request!!.logicalRequestId)
            assertEquals(0, event.request!!.jobIndex)
            assertEquals("", event.request!!.partPath)
            assertNull(event.request!!.respondedAtEpochMs)
            assertEquals("RECOVERED_BY_DOCUMENT_READY", event.disposition(doc.status))
            val calls = fake.requests.size
            assertTrue(p.summary(id).contains("MATCHES_SAVED_PIPELINE_RESULTS"))
            assertTrue(p.processor().process(id) is ProcessOutcome.Skipped)
            assertEquals(doc.failureJson, p.documents.get(id)!!.failureJson)
            assertEquals(calls, fake.requests.size)
        }
    }

    @Test fun existingCardsDoNotConcealUnfinishedJobsOrRunningState() = runTest {
        val fake = FakeLlmClient()
        TestPipeline(tmp.newFolder(), fake).use { p ->
            val id = p.create()
            p.processor().process(id)
            val before = p.db.cardDao().getByDocument(id)
            val job = p.db.jobDao().getByKind(id, JobKind.CONSOLIDATE).single()
            p.db.jobDao().update(job.copy(status = JobStatus.PENDING, responseJson = null))
            p.documents.requeue(id)
            assertTrue(p.summary(id).contains("Document status: QUEUED"))
            assertTrue(p.summary(id).contains("UNFINISHED_JOBS"))
            p.processor().process(id, localConsolidationOnly = true)
            val copied = p.summary(id)
            assertTrue(copied.contains("Document status: FAILED"))
            assertTrue(copied.contains("Historical diagnostic disposition: DOCUMENT_FAILED"))
            assertFalse(copied.contains("RECOVERED_BY_DOCUMENT_READY"))
            assertEquals(before, p.db.cardDao().getByDocument(id))
            assertTrue(p.processor().process(id, consolidationOnly = true) is ProcessOutcome.Ready)
            assertTrue(p.summary(id).contains("MATCHES_SAVED_PIPELINE_RESULTS"))
            assertTrue(p.summary(id).contains("RECOVERED_BY_DOCUMENT_READY"))
        }
    }

    @Test fun installedLegacyReadyNeedsNoReprocessingOrHistoricalRewrite() = runTest {
        val fake = FakeLlmClient()
        TestPipeline(tmp.newFolder(), fake).use { p ->
            val id = p.create()
            p.processor().process(id)
            val old = """{"source":"TEXT","stage":"CONSOLIDATING","code":"UNKNOWN","observedAtEpochMs":1791059173074}"""
            p.db.documentDao().setFailure(id, old)
            val calls = fake.requests.size
            val copied = p.summary(id)
            assertTrue(copied.contains("RECOVERED_BY_DOCUMENT_READY"))
            assertTrue(copied.contains("Recovered at: UNKNOWN"))
            assertTrue(copied.contains("MATCHES_SAVED_PIPELINE_RESULTS"))
            assertFalse(copied.contains("The bank"))
            assertEquals(calls, fake.requests.size)
            assertEquals(old, p.documents.get(id)!!.failureJson)
        }
    }
}
