package pro.perfectproduct.cramin.chatgpt

import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import pro.perfectproduct.cramin.data.db.*
import pro.perfectproduct.cramin.data.repo.NewDocument
import pro.perfectproduct.cramin.llm.*
import pro.perfectproduct.cramin.pipeline.*
import pro.perfectproduct.cramin.testing.*
import pro.perfectproduct.cramin.util.Lang

@RunWith(RobolectricTestRunner::class)
class PlanPipelineTest {
    @get:Rule val tmp=TemporaryFolder()
    @Test fun planFailureNeverReadyAndResumeUsesSavedProviderAndValidatedCache() = runBlocking {
        for (error in listOf(LlmException.Network("synthetic interruption"),LlmException.ChatGpt(ChatGptFailure.LIMIT),LlmException.InvalidResponse("synthetic refusal"))) {
            var fail=true
            val fake=FakeLlmClient()
            val seen=mutableListOf<LlmRequest>()
            val llm=object:LlmClient {
                override suspend fun complete(request:LlmRequest):LlmResponse {
                    seen += request
                    assertEquals(TextProvider.CHATGPT_PLAN,request.provider)
                    if (fail && request.role==ModelRole.TRANSLATE) throw error
                    return fake.complete(request).let { it.copy(usage=it.usage.copy(costUsd=null)) }
                }
            }
            TestPipeline(tmp.newFolder(),llm).use { p ->
                p.effective=ChatGptConfig.resolve("synthetic-slug",TestPipeline.FAKE_CONFIG)
                val id=p.documents.create(NewDocument.Text(Fixtures.text(Lang.EN),null,Lang.RU,Lang.EN))
                val first=p.processor().process(id)
                assertTrue(first is ProcessOutcome.Failed)
                assertEquals(DocStatus.FAILED,p.documents.get(id)!!.status)
                assertTrue(p.db.cardDao().getByDocument(id).isEmpty())
                assertEquals(1,seen.count { it.role==ModelRole.BRIEF })
                val pinned=p.documents.get(id)!!.modelsSnapshotJson
                // Global settings change cannot reroute a resumed plan job to OpenRouter.
                p.effective=ModelConfigResolver.resolve(null,null,TestPipeline.FAKE_CONFIG,null)
                fail=false
                val second=p.processor().process(id)
                assertTrue("$second",second is ProcessOutcome.Ready)
                assertEquals(pinned,p.documents.get(id)!!.modelsSnapshotJson)
                assertEquals(1,seen.count { it.role==ModelRole.BRIEF })
                assertNull(p.documents.get(id)!!.costUsd)
                assertTrue(p.db.cardDao().getByDocument(id).isNotEmpty())
            }
        }
    }

    @Test fun providerIsStoredInTheInitialDocumentInsertBeforeAnyWorkerRuns() = runBlocking {
        TestPipeline(tmp.newFolder(),FakeLlmClient()).use { p ->
            val snapshot=ProcessingSnapshot.capture(ChatGptConfig.resolve("synthetic-slug",TestPipeline.FAKE_CONFIG),null)
            val id=p.documents.create(NewDocument.Text("A synthetic river bank.",null,Lang.RU,Lang.EN),snapshot.encode(),DocumentProcessor.PIPELINE_VERSION)
            val document=p.documents.get(id)!!
            assertEquals(DocStatus.QUEUED,document.status)
            assertEquals(snapshot,ProcessingSnapshot.decode(document.modelsSnapshotJson!!))
            assertEquals(DocumentProcessor.PIPELINE_VERSION,document.pipelineVersion)
            assertTrue(p.db.jobDao().getByDocument(id).isEmpty())
        }
    }
}
