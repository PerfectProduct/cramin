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

/** Synthetic source languages: these fixtures make no claims about the real video's language. */
@RunWith(RobolectricTestRunner::class)
class YoutubeLanguageTest {
    @get:Rule val tmp = TemporaryFolder()

    private suspend fun check(source: Lang, target: Lang, manualHint: Lang? = null) {
        val languages = mutableListOf<Lang?>()
        val raw = Fixtures.text(source)
        val fake = FakeLlmClient()
        val extractor = object : SourceExtractor {
            override suspend fun extract(document: DocumentEntity, files: DocumentFiles): Extracted = Extracted.Audio(
                File(files.audioDir(document.id), "full.m4a").apply { writeText("synthetic") }, null, source, 1,
                TextProvenance("YOUTUBE_AUDIO_STT", source.code, "ORIGINAL"))
        }
        val audioSegmenter = object : AudioSegmenter {
            override suspend fun split(input: File, outDir: File, maxPartSeconds: Int) = listOf(AudioPart(input, 1))
        }
        val transcriber = object : Transcriber {
            override suspend fun transcribe(part: AudioPart, lang: Lang?, model: String): Transcript {
                languages += lang
                return Transcript(raw, null)
            }
        }
        TestPipeline(tmp.newFolder(), fake, extraExtractors = mapOf(SourceType.YOUTUBE to extractor),
            transcriber = transcriber, audioSegmenter = audioSegmenter).use { p ->
            val id = p.documents.create(NewDocument.Youtube("https://example.invalid/synthetic", target))
            assertEquals("", p.documents.get(id)!!.sourceLang)
            if (manualHint != null) p.documents.setSourceLang(id, manualHint)
            val result = p.processor().process(id)
            if (source == target) {
                assertTrue(result is ProcessOutcome.Failed && result.code == ErrorCode.SAME_LANGUAGE)
                assertTrue("STT must not run for a known same-language pair", languages.isEmpty())
                assertTrue(fake.requests.isEmpty())
                assertTrue(p.db.jobDao().getByKind(id, JobKind.STT).isEmpty())
            } else {
                assertTrue("$result", result is ProcessOutcome.Ready)
                assertEquals(listOf(source), languages)
                assertEquals(source.code, p.documents.get(id)!!.sourceLang)
                assertEquals(target.code, p.documents.get(id)!!.targetLang)
                assertEquals(raw, p.files.sourceText(id).readText())
                assertEquals(source.code, p.files.textProvenance(id).sttLanguage)
            }
        }
    }
    @Test fun resumeUsesLanguageBoundToSavedSourceWithoutFetchingOrStt() = runTest {
        TestPipeline(tmp.newFolder(), FakeLlmClient()).use { p ->
            val id = p.documents.create(NewDocument.Youtube("https://example.invalid/synthetic", Lang.HE))
            p.files.writeSourceText(id, Fixtures.text(Lang.EN), TextProvenance(
                "YOUTUBE_AUDIO_STT", "en", "ORIGINAL", "en", "fake/stt"))
            p.documents.setSourceLang(id, Lang.RU)
            assertTrue(p.processor().process(id) is ProcessOutcome.Ready)
            assertEquals("en", p.documents.get(id)!!.sourceLang)
            assertTrue(p.db.jobDao().getByKind(id, JobKind.STT).isEmpty())
        }
    }
    @Test fun russianSourceEnglishLearning() = runTest { check(Lang.RU, Lang.EN) }
    @Test fun englishSourceRussianLearning() = runTest { check(Lang.EN, Lang.RU) }
    @Test fun matchingLearningLanguageStopsBeforeStt() = runTest { check(Lang.EN, Lang.EN) }
    @Test fun selectedTrackLanguageWinsOverEarlierManualHint() = runTest { check(Lang.EN, Lang.HE, Lang.RU) }
}
