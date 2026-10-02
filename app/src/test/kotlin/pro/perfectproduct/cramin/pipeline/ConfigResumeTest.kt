package pro.perfectproduct.cramin.pipeline

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import pro.perfectproduct.cramin.data.repo.NewDocument
import pro.perfectproduct.cramin.llm.*
import pro.perfectproduct.cramin.testing.*
import pro.perfectproduct.cramin.util.Lang

@RunWith(RobolectricTestRunner::class)
class ConfigResumeTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun sttRetryUsesTheSameFrozenModel() = runTest {
        val models = mutableListOf<String>()
        var fail = true
        val audio = tmp.newFile("synthetic.m4a")
        val transcriber = object : pro.perfectproduct.cramin.transcribe.Transcriber {
            override suspend fun transcribe(part: pro.perfectproduct.cramin.transcribe.AudioPart, lang: Lang?, model: String): pro.perfectproduct.cramin.transcribe.Transcript {
                models += model
                if (fail) throw PipelineException(ErrorCode.NETWORK, "synthetic")
                return pro.perfectproduct.cramin.transcribe.Transcript(Fixtures.text(Lang.EN), null)
            }
        }
        val extractor = object : pro.perfectproduct.cramin.ingest.SourceExtractor {
            override suspend fun extract(document: pro.perfectproduct.cramin.data.db.DocumentEntity, files: pro.perfectproduct.cramin.data.repo.DocumentFiles) =
                pro.perfectproduct.cramin.ingest.Extracted.Audio(audio, null, Lang.EN, 1)
        }
        val segmenter = object : pro.perfectproduct.cramin.transcribe.AudioSegmenter {
            override suspend fun split(input: java.io.File, outDir: java.io.File, maxPartSeconds: Int) = listOf(pro.perfectproduct.cramin.transcribe.AudioPart(audio, 1))
        }
        TestPipeline(tmp.newFolder(), FakeLlmClient(), extraExtractors = mapOf(pro.perfectproduct.cramin.data.db.SourceType.YOUTUBE to extractor), transcriber = transcriber, audioSegmenter = segmenter).use { p ->
            val id = p.documents.create(NewDocument.Youtube("https://www.youtube.com/watch?v=synthetic01", Lang.RU))
            assertTrue(p.processor().process(id) is ProcessOutcome.Failed)
            val old = p.effective.role(ModelRole.STT).model
            p.effective = p.effective.copy(roles = p.effective.roles.mapValues { (_, role) -> role.copy(model = "changed/" + role.role.key) })
            fail = false
            p.documents.requeue(id)
            val result = p.processor().process(id)
            assertTrue("$result models=$models", result is ProcessOutcome.Ready)
            assertEquals(listOf(old, old), models)
        }
    }

    @Test fun retryKeepsOriginalConfigurationAndExplicitReprocessGetsNewOne() = runTest {
        val fake = FakeLlmClient()
        TestPipeline(tmp.newFolder(), fake).use { p ->
            val original = p.effective
            fake.errorInjector = { request, _ -> if (request.role == ModelRole.TRANSLATE) LlmException.Network("synthetic") else null }
            val id = p.documents.create(NewDocument.Text(Fixtures.text(Lang.EN), null, Lang.RU, Lang.EN))
            assertTrue(p.processor().process(id) is ProcessOutcome.Failed)
            val snapshot = p.documents.get(id)!!.modelsSnapshotJson
            p.effective = original.copy(roles = original.roles.mapValues { (_, role) -> role.copy(model = "new/" + role.role.key, temperature = 0.8) }, pipeline = original.pipeline.copy(extractChunkWords = 30))
            val beforeRetry = fake.requests.size
            fake.errorInjector = null
            p.documents.requeue(id)
            assertTrue(p.processor().process(id) is ProcessOutcome.Ready)
            assertEquals(snapshot, p.documents.get(id)!!.modelsSnapshotJson)
            assertTrue(fake.requests.drop(beforeRetry).all { it.model == original.role(it.role).model })
            p.documents.prepareReprocess(id)
            val beforeNewRun = fake.requests.size
            assertTrue(p.processor().process(id) is ProcessOutcome.Ready)
            assertTrue(fake.requests.drop(beforeNewRun).all { it.model.startsWith("new/") })
            assertNotEquals(snapshot, p.documents.get(id)!!.modelsSnapshotJson)
        }
    }
}
