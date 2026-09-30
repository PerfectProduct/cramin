package pro.perfectproduct.cramin.live

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import pro.perfectproduct.cramin.LiveApi
import pro.perfectproduct.cramin.data.db.DocStatus
import pro.perfectproduct.cramin.data.db.DocumentEntity
import pro.perfectproduct.cramin.data.db.SourceType
import pro.perfectproduct.cramin.data.repo.DocumentFiles
import pro.perfectproduct.cramin.ingest.ArticleExtractor
import pro.perfectproduct.cramin.ingest.Extracted
import pro.perfectproduct.cramin.ingest.YoutubeExtractor
import pro.perfectproduct.cramin.transcribe.MediaAudioSegmenter
import pro.perfectproduct.cramin.transcribe.OpenRouterSttTranscriber
import pro.perfectproduct.cramin.util.Lang
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Живые тесты источников (SPEC §14.3а): статья по реальному URL, YouTube с авторскими субтитрами,
 * YouTube без авторских субтитров через AudioSegmenter + STT. Видео выбраны разведкой
 * (docs/decision-log.md CRM-DL-019): TED-Ed с авторскими дорожками en/ru/iw и «David After Dentist»
 * (1:59, речь, только автоматические субтитры).
 */
@LiveApi
@RunWith(AndroidJUnit4::class)
class SourcesLiveTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val http = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
    private val files by lazy { DocumentFiles(File(context.cacheDir, "live-${System.nanoTime()}").apply { mkdirs() }) }

    @Before
    fun setUp() = DeviceLiveEnv.quietLogs()

    @Test
    fun articleFromRealUrl() = runBlocking {
        val doc = doc(1, SourceType.URL, ARTICLE_URL)
        val result = ArticleExtractor(http).extract(doc, files) as Extracted.Text
        assertTrue(result.text.length > 1000)
        assertTrue("title=${result.title}", result.title?.contains("Flashcard", ignoreCase = true) == true)
        assertTrue(result.text.contains("flashcard", ignoreCase = true))
        assertTrue(result.text.split("\n\n").size >= 3)
    }

    @Test
    fun youtubeWithAuthoredSubtitles() = runBlocking {
        val doc = doc(2, SourceType.YOUTUBE, VIDEO_WITH_SUBTITLES)
        val result = YoutubeExtractor(http).extract(doc, files)
        assertTrue("ожидали текст субтитров, получили $result", result is Extracted.Text)
        result as Extracted.Text
        assertEquals(Lang.EN, result.langHint)
        assertTrue(result.text.length > 2000)
        assertTrue(result.text.contains("cat", ignoreCase = true))
        assertTrue("нет абзацев по паузам", result.text.contains("\n\n"))
        assertTrue(result.title?.contains("cats", ignoreCase = true) == true)
    }

    @Test
    fun youtubeWithoutAuthoredSubtitlesIsTranscribed() = runBlocking {
        val key = DeviceLiveEnv.requireKey()
        val doc = doc(3, SourceType.YOUTUBE, VIDEO_WITHOUT_SUBTITLES)
        val result = YoutubeExtractor(http).extract(doc, files)
        assertTrue("ожидали аудио, получили $result", result is Extracted.Audio)
        result as Extracted.Audio
        assertTrue(result.file.length() > 200_000)
        assertTrue(result.durationSeconds in 60..180)
        // Куски по 60 с, чтобы проверить нарезку на коротком видео (в проде — 600 с).
        val parts = MediaAudioSegmenter().split(result.file, files.audioDir(3), maxPartSeconds = 60)
        assertTrue("parts=${parts.size}", parts.size in 2..3)
        assertTrue(parts.all { it.file.length() > 50_000 })
        val stt = OpenRouterSttTranscriber(http, keyProvider = { key })
        val model = pro.perfectproduct.cramin.llm.ModelsConfigFile.parse(
            context.assets.open("models.json").use { it.readBytes().toString(Charsets.UTF_8) },
        ).roles.getValue("stt").model
        val texts = parts.map { p ->
            val t = stt.transcribe(p, Lang.EN, model)
            DeviceLiveEnv.record(model, t.costUsd)
            t.text
        }
        val text = texts.joinToString(" ")
        assertTrue("text=${text.take(200)}", text.length > 100)
        assertTrue(text.contains("life", ignoreCase = true) || text.contains("dentist", ignoreCase = true) || text.contains("feel", ignoreCase = true))
    }

    private fun doc(id: Long, type: SourceType, ref: String) = DocumentEntity(
        id = id, title = "live", emoji = "📄", sourceType = type, sourceRef = ref, sourceLang = "", targetLang = "ru",
        status = DocStatus.QUEUED, progress = 0f, errorCode = null, errorMessage = null, direction = null, pipelineVersion = 0,
        briefJson = null, modelsSnapshotJson = null, promptTokens = 0, completionTokens = 0, costUsd = null, audioSeconds = 0,
        wordCount = 0, createdAt = 0, updatedAt = 0,
    )

    companion object {
        const val ARTICLE_URL = "https://en.wikipedia.org/wiki/Flashcard"
        /** TED-Ed «Why do cats act so weird?» — авторские субтитры en/ru/iw, 4:58. */
        const val VIDEO_WITH_SUBTITLES = "https://www.youtube.com/watch?v=sI8NsYIyQ2A"
        /** «David After Dentist» — 1:59, речь, только автоматические субтитры. */
        const val VIDEO_WITHOUT_SUBTITLES = "https://www.youtube.com/watch?v=txqiwrbYGrs"
    }
}
