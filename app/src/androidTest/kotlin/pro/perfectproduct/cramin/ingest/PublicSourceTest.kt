package pro.perfectproduct.cramin.ingest

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import pro.perfectproduct.cramin.ExternalSource
import pro.perfectproduct.cramin.data.db.SourceType
import pro.perfectproduct.cramin.data.repo.NewDocument
import pro.perfectproduct.cramin.pipeline.*
import pro.perfectproduct.cramin.testing.TestContainer
import pro.perfectproduct.cramin.util.Lang
import java.io.File

/** Real free extraction; never invokes the production LLM/STT clients. */
@ExternalSource
@RunWith(AndroidJUnit4::class)
class PublicSourceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun staticArticleToFakePipeline() = runBlocking {
        checkSource("nasa-article", "https://spaceplace.nasa.gov/blue-sky/en/", false, false)
    }
    @Test fun authoredYoutubeCaptionsToFakePipeline() = runBlocking {
        checkSource("ted-authored", "https://www.youtube.com/watch?v=qp0HIF3SfI4", true, false)
    }
    @Test fun youtubeAudioBeforePaidStt() = runBlocking {
        checkSource("bunny-audio", "https://www.youtube.com/watch?v=aqz-KE-bpKQ", true, true)
    }

    private suspend fun checkSource(name: String, url: String, youtube: Boolean, expectAudio: Boolean) {
        assumeTrue("Explicit free-source opt-in required", InstrumentationRegistry.getArguments().getString("freeSources") == "true")
        val container = TestContainer(context)
        val proof = File(context.getExternalFilesDir(null), "source-proof").apply { mkdirs() }
        var stage = "FETCHING"
        try {
            val id = container.documentRepository.create(if (youtube) NewDocument.Youtube(url, Lang.RU) else NewDocument.Url(url, Lang.RU))
            val document = container.documentRepository.get(id)!!
            val extractor = if (youtube) YoutubeExtractor(OkHttpClient()) else ArticleExtractor(OkHttpClient())
            val extracted = extractor.extract(document, container.files)
            if (expectAudio) {
                assertTrue("Source returned captions instead of the requested audio path", extracted is Extracted.Audio)
                val audio = extracted as Extracted.Audio
                assertTrue(audio.file.length() > 1_000)
                File(proof, "$name.txt").writeText("FETCHING success; audio bytes=${audio.file.length()}; paid STT not invoked; languageHint=${audio.langHint?.code ?: "unknown"}\n")
                audio.file.delete()
                return
            }
            assertTrue("Authored text required; audio fallback is not a captions success", extracted is Extracted.Text)
            val text = extracted as Extracted.Text
            stage = "LANGUAGE"
            assertTrue(text.text.isNotBlank())
            assertEquals(Lang.EN, text.langHint ?: LangDetector.detect(text.text))
            val original = container.processorDeps()
            val captured = object : SourceExtractor {
                override suspend fun extract(document: pro.perfectproduct.cramin.data.db.DocumentEntity, files: pro.perfectproduct.cramin.data.repo.DocumentFiles) = text
            }
            val deps = ProcessorDeps(original.db, original.files, container.fakeLlm,
                mapOf(document.sourceType to captured), null, null, { container.effective }, { null }, original.stoplists,
                original.segmenter, original.usage, original.clock)
            stage = "FAKE_PIPELINE"
            val result = DocumentProcessor(deps).process(id)
            assertTrue("$result", result is ProcessOutcome.Ready)
            File(proof, "$name.txt").writeText("FETCHING success; text chars=${text.text.length}; LANGUAGE en; fake LLM READY cards=${(result as ProcessOutcome.Ready).cardCount}; paid calls=0\n")
        } catch (t: Throwable) {
            val code = PipelineException.from(t).code
            val types = generateSequence(t) { it.cause }.take(8).joinToString(" -> ") { it.javaClass.simpleName }
            File(proof, "$name.txt").writeText("stage=$stage; code=$code; causes=$types; paid calls=0; original owner input not tested\n")
            // No raw provider body, URL query, source text or exception message in test reports.
            throw AssertionError("$name stage=$stage code=$code causes=$types")
        } finally { container.db.close() }
    }
}
