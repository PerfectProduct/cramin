package pro.perfectproduct.cramin.pipeline

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import pro.perfectproduct.cramin.data.db.*
import pro.perfectproduct.cramin.data.repo.*
import pro.perfectproduct.cramin.ingest.*
import pro.perfectproduct.cramin.testing.*
import pro.perfectproduct.cramin.transcribe.*
import pro.perfectproduct.cramin.util.Lang
import java.io.File

@RunWith(RobolectricTestRunner::class)
class TranscriptOriginTest {
    @get:Rule val tmp = TemporaryFolder()

    private suspend fun checkRetry(changed: Boolean, legacy: Boolean = false, changedTrack: Boolean = false, failBeforeFirst: Boolean = false) {
        val raw = "В simple terms Cancer RAG. Это problem в ней в ней в ней."
        var calls = 0
        var fail = true
        var bytes = "original audio"
        var trackId = "ru-original"
        val extractor = object : SourceExtractor {
            override suspend fun extract(document: DocumentEntity, files: DocumentFiles): Extracted {
                val file = File(files.audioDir(document.id), "full.m4a").apply { writeText(bytes) }
                return Extracted.Audio(file, null, Lang.RU, 2, TextProvenance("YOUTUBE_AUDIO_STT", "ru", "ORIGINAL", trackId = trackId))
            }
        }
        val segmenter = object : AudioSegmenter {
            override suspend fun split(input: File, outDir: File, maxPartSeconds: Int) = (0..1).map {
                AudioPart(File(outDir, "part-$it.m4a").apply { writeText("$bytes:$it") }, 1)
            }
        }
        val transcriber = object : Transcriber {
            override suspend fun transcribe(part: AudioPart, lang: Lang?, model: String): Transcript {
                calls++
                assertEquals(Lang.RU, lang)
                if (fail && (failBeforeFirst || part.file.name == "part-1.m4a")) throw PipelineException(ErrorCode.NETWORK, "synthetic")
                return Transcript(if (part.file.name == "part-0.m4a") raw else "Последняя техника.", null)
            }
        }
        TestPipeline(tmp.newFolder(), FakeLlmClient(), extraExtractors = mapOf(SourceType.YOUTUBE to extractor),
            transcriber = transcriber, audioSegmenter = segmenter).use { p ->
            val id = p.documents.create(NewDocument.Youtube("https://example.invalid/private", Lang.EN))
            assertTrue(p.processor().process(id) is ProcessOutcome.Failed)
            assertEquals(if (failBeforeFirst) 1 else 2, calls)
            if (legacy) {
                val done = p.db.jobDao().getByKind(id, JobKind.STT).first { it.status == JobStatus.DONE }
                p.db.jobDao().update(done.copy(finishReason = null))
            }
            if (changed) bytes = "different dub"
            if (changedTrack) trackId = "another-track"
            fail = false
            p.documents.requeue(id)
            val outcome = p.processor().process(id)
            if (changed || legacy || changedTrack) {
                assertTrue(outcome is ProcessOutcome.Failed)
                assertEquals(if (failBeforeFirst) 1 else 2, calls) // No paid work or mixing when identity is unverified.
                assertFalse(p.files.sourceText(id).exists())
            } else {
                assertTrue("$outcome", outcome is ProcessOutcome.Ready)
                assertEquals(3, calls) // First part was reused exactly once.
                assertEquals("$raw\n\nПоследняя техника.", p.files.sourceText(id).readText())
                val sentences = p.db.sentenceDao().getByDocument(id)
                assertTrue(sentences.any { it.text == "Это problem в ней в ней в ней." })
                for (card in p.cards.deckCards(id, DeckFilter.ALL)) for (sense in card.senses) sense.example?.let { example ->
                    assertTrue(sentences.any { it.text == example.sentence })
                    assertTrue(example.translation?.startsWith("tr_") == true)
                }
                val summary = DocumentStateSummary.copyText(p.db, id, p.deps.stoplists, "test", 34, p.files)
                assertTrue(summary.contains("Text provenance: YOUTUBE_AUDIO_STT"))
                assertTrue(summary.contains("STT request language: ru"))
                assertTrue(summary.contains("Track ID: ru-original"))
                assertFalse(summary.contains("Cancer"))
                assertFalse(summary.contains("example.invalid"))
                // Legacy/missing metadata never borrows today's config or guesses a track.
                File(p.files.dir(id), "text-provenance.json").delete()
                val old = DocumentStateSummary.copyText(p.db, id, p.deps.stoplists, "test", 34, p.files)
                assertTrue(old.contains("Text provenance: UNKNOWN"))
                assertTrue(old.contains("completedParts=2; models=fake/stt"))
            }
        }
    }
    @Test fun changedTrackAfterFailedFirstRequestIsRejected() = runTest { checkRetry(false, changedTrack = true, failBeforeFirst = true) }
    @Test fun changedTrackCannotRelabelCachedParts() = runTest { checkRetry(false, changedTrack = true) }
    @Test fun matchingRetryPreservesOriginalAndSeparateTranslation() = runTest { checkRetry(false) }
    @Test fun differentAudioCannotMixWithCachedParts() = runTest { checkRetry(true) }
    @Test fun legacyPartsWithoutIdentityCannotMixWithNewAudio() = runTest { checkRetry(false, legacy = true) }
}
