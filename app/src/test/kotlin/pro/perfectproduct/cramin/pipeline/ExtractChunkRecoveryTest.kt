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

/** In-memory Room and synthetic text/fake API; never reads or processes an owner's document. */
@RunWith(RobolectricTestRunner::class)
class ExtractChunkRecoveryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val text get() = List(8) { Fixtures.text(Lang.EN) }.joinToString("\n\n")
    private fun config(words: Int) = TestPipeline.FAKE_CONFIG.copy(pipeline =
        requireNotNull(TestPipeline.FAKE_CONFIG.pipeline).copy(extractChunkWords = words, extractConcurrency = 1))
    private fun newDocument() = NewDocument.Text(text, null, Lang.RU, Lang.EN)
    private suspend fun plan(p: TestPipeline, id: Long, words: Int): List<IntRange> {
        val sentences = p.db.sentenceDao().getByDocument(id)
        return ChunkPlanner.plan(p.db.segmentDao().getByDocument(id).map { segment ->
            SegmentSpan(segment.firstSentenceIdx, segment.lastSentenceIdx, sentences.filter {
                it.idx in segment.firstSentenceIdx..segment.lastSentenceIdx
            }.sumOf { WordCounter.count(it.text) })
        }, words)
    }
    private fun ranges(jobs: List<JobEntity>) = jobs.map { requireNotNull(it.rangeStart)..requireNotNull(it.rangeEnd) }

    @Test fun old700SnapshotAndCreatedJobsResumeUnchangedWhileFreshDocumentGets350() = runTest {
        val fake = FakeLlmClient()
        var extracts = 0
        var failTranslation = true
        fake.errorInjector = { r, _ ->
            when {
                r.role == ModelRole.TRANSLATE && failTranslation -> RuntimeException("synthetic pre-extraction interruption")
                r.role == ModelRole.EXTRACT && ++extracts == 2 -> RuntimeException("synthetic extraction interruption")
                else -> null
            }
        }
        TestPipeline(tmp.newFolder(), fake, config = config(700)).use { p ->
            val other = p.documents.create(newDocument())
            val otherBefore = p.documents.get(other)
            val id = p.documents.create(newDocument())
            assertTrue(p.processor().process(id) is ProcessOutcome.Failed)
            val snapshot = requireNotNull(p.documents.get(id)?.modelsSnapshotJson)
            assertTrue(p.db.jobDao().getByKind(id, JobKind.EXTRACT).isEmpty())
            p.effective = p.effective.copy(pipeline = p.effective.pipeline.copy(extractChunkWords = PipelineParams().extractChunkWords))
            failTranslation = false
            p.documents.requeue(id)
            assertTrue(p.processor().process(id) is ProcessOutcome.Failed)
            assertEquals(snapshot, p.documents.get(id)?.modelsSnapshotJson)
            val jobs = p.db.jobDao().getByKind(id, JobKind.EXTRACT)
            val done = jobs.filter { it.status == JobStatus.DONE }
            assertEquals(1, done.size)
            assertTrue(jobs.size >= 3)
            assertEquals(plan(p, id, 700), ranges(jobs))
            assertNotEquals(ranges(jobs), plan(p, id, 350))
            val translateCalls = fake.callsFor(ModelRole.TRANSLATE)
            val before = fake.requests.size
            fake.errorInjector = null
            p.documents.requeue(id)
            assertTrue(p.processor().process(id) is ProcessOutcome.Ready)
            assertEquals(snapshot, p.documents.get(id)?.modelsSnapshotJson)
            assertEquals(700, ProcessingSnapshot.decode(snapshot).config.pipeline.extractChunkWords)
            val after = p.db.jobDao().getByKind(id, JobKind.EXTRACT)
            assertEquals(jobs.map { it.id }, after.map { it.id })
            assertEquals(ranges(jobs), ranges(after))
            done.forEach { assertEquals(it, p.db.jobDao().getById(it.id)) }
            assertEquals(translateCalls, fake.callsFor(ModelRole.TRANSLATE))
            assertEquals(jobs.filter { it.status != JobStatus.DONE }.map { requireNotNull(it.rangeStart)..requireNotNull(it.rangeEnd) },
                fake.requests.drop(before).filter { it.role == ModelRole.EXTRACT }.map {
                    val pairs = Messages.parsePairs(it.user)
                    pairs.first().sentences.first().idx..pairs.last().sentences.last().idx
                })
            assertEquals(otherBefore, p.documents.get(other))
            assertTrue(p.db.jobDao().getByDocument(other).isEmpty())

            val fresh = p.documents.create(newDocument())
            assertTrue(p.processor().process(fresh) is ProcessOutcome.Ready)
            assertEquals(350, ProcessingSnapshot.decode(requireNotNull(p.documents.get(fresh)?.modelsSnapshotJson)).config.pipeline.extractChunkWords)
            assertEquals(plan(p, fresh, 350), ranges(p.db.jobDao().getByKind(fresh, JobKind.EXTRACT)))
        }
    }

    @Test fun new350ParseRetryLengthSplitAndInterruptionKeepDoneJobsAndRecoverAllUnits() = runTest {
        val fake = FakeLlmClient()
        var extracts = 0
        fake.interceptor = { r, _ -> if (r.role == ModelRole.EXTRACT) when (++extracts) {
            2 -> LlmResponse("invalid", "stop", LlmUsage.ZERO, r.model)
            3 -> LlmResponse("{}", "length", LlmUsage.ZERO, r.model)
            5 -> throw RuntimeException("synthetic interruption after first split half")
            else -> null
        } else null }
        val current = config(PipelineParams().extractChunkWords)
        TestPipeline(tmp.newFolder(), fake, config = current).use { p ->
            val id = p.documents.create(newDocument())
            assertTrue(p.processor().process(id) is ProcessOutcome.Failed)
            val snapshot = p.documents.get(id)?.modelsSnapshotJson
            val jobs = p.db.jobDao().getByKind(id, JobKind.EXTRACT)
            assertEquals(plan(p, id, 350), ranges(jobs))
            val done = jobs.first()
            assertEquals(JobStatus.DONE, done.status)
            val interrupted = jobs[1]
            assertEquals(3, interrupted.attempts) // invalid, length retry, successful first half
            assertNotEquals(JobStatus.DONE, interrupted.status)
            assertNull(interrupted.responseJson) // incomplete half never masquerades as complete u
            val segments = p.db.segmentDao().getByDocument(id)
            val before = fake.requests.size
            fake.interceptor = null
            p.effective = p.effective.copy(pipeline = p.effective.pipeline.copy(extractChunkWords = 700))
            p.documents.requeue(id)
            assertTrue(p.processor().process(id) is ProcessOutcome.Ready)
            assertEquals(snapshot, p.documents.get(id)?.modelsSnapshotJson)
            val complete = p.db.jobDao().getByKind(id, JobKind.EXTRACT)
            assertEquals(jobs.map { it.id }, complete.map { it.id })
            assertEquals(ranges(jobs), ranges(complete))
            assertEquals(done, p.db.jobDao().getById(done.id))
            assertEquals(segments, p.db.segmentDao().getByDocument(id))
            assertEquals(jobs.size - 1, fake.requests.drop(before).count { it.role == ModelRole.EXTRACT })
            assertTrue(fake.requests.drop(before).none { it.role == ModelRole.TRANSLATE || it.role == ModelRole.BRIEF })
            assertTrue(complete.all { it.status == JobStatus.DONE })
            val units = complete.flatMap { LlmJson.parse<StoredExtraction>(requireNotNull(it.responseJson)).u }
            TestPipeline(tmp.newFolder(), FakeLlmClient(), config = current).use { baseline ->
                val clean = baseline.documents.create(newDocument())
                assertTrue(baseline.processor().process(clean) is ProcessOutcome.Ready)
                assertEquals(baseline.db.jobDao().getByKind(clean, JobKind.EXTRACT).flatMap {
                    LlmJson.parse<StoredExtraction>(requireNotNull(it.responseJson)).u
                }, units)
            }
        }
    }
}
