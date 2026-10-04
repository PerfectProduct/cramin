package pro.perfectproduct.cramin.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import pro.perfectproduct.cramin.app.*
import pro.perfectproduct.cramin.app.theme.CraminTheme
import pro.perfectproduct.cramin.data.db.*
import pro.perfectproduct.cramin.testing.TestContainer
import java.io.File

class NavigationReviewTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val container = TestContainer(context)
    @After fun close() { container.db.close() }
    @Test fun light100() = review(1f, false, false)
    @Test fun dark150() = review(1.5f, true, false)
    @Test fun dark200Rtl() = review(2f, true, true)
    private suspend fun seedDocument(legacy: Boolean = false, failed: Boolean = false): Long {
        val db = container.db
        val docId = db.documentDao().insert(
            DocumentEntity(
                title = if (legacy) "Материал без категорий" else if (failed) "Ошибка импорта PDF" else "Линейные модели: длинное название материала для проверки навигации и переноса строк", emoji = "📚", sourceType = SourceType.TEXT, sourceRef = "", sourceLang = "en", targetLang = "ru",
                status = if (failed) DocStatus.FAILED else DocStatus.READY, progress = 1f, errorCode = if (failed) "PDF_NO_TEXT" else null, errorMessage = null, direction = null, pipelineVersion = 1,
                briefJson = null, modelsSnapshotJson = null, promptTokens = 1, completionTokens = 1, costUsd = 0.01, audioSeconds = 0,
                wordCount = 10, createdAt = 1L, updatedAt = 1L,
            ),
        )
        val sIds = db.sentenceDao().insertAll(listOf(SentenceEntity(documentId = docId, idx = 0, paragraphIdx = 0, text = "The bank was closed.", segmentId = null)))
        val seg = db.segmentDao().insert(SegmentEntity(documentId = docId, firstSentenceIdx = 0, lastSentenceIdx = 0, translation = "Банк был закрыт."))
        db.sentenceDao().assignSegment(docId, 0, 0, seg)
        for ((i, pair) in listOf("bank" to "банк", "closed" to "закрытый").withIndex()) {
            val cardId = db.cardDao().insertCard(
                CardEntity(documentId = docId, lemmaKey = "${pair.first}|NOUN", meaningKey = pair.second, lemma = pair.first, lemmaVocalized = null, pos = Pos.NOUN, lang = "en", targetLang = "ru", status = CardStatus.NEW, starred = i == 0, category = if (legacy) null else pro.perfectproduct.cramin.data.db.TopicCategory.entries[i], firstSentenceIdx = i, updatedAt = 0L),
            )
            val senseId = db.cardDao().insertSense(SenseEntity(cardId = cardId, idx = 0, translation = pair.second, exampleOccurrenceId = null))
            val occ = db.cardDao().insertOccurrence(OccurrenceEntity(cardId = cardId, senseId = senseId, sentenceId = sIds[0], surface = pair.first, targetSurface = null, start = 4, end = 8, targetStart = null, targetEnd = null, isExample = true))
            db.cardDao().setSenseExample(senseId, occ)
        }
        return docId
    }


    private fun review(scale: Float, dark: Boolean, rtl: Boolean) {
        val id = runBlocking {
            container.settingsStore.setOnboardingDone(true)
            container.settingsStore.setAutoplayIntervals(10000, 10000)
            seedDocument(legacy = true)
            seedDocument(failed = true)
            seedDocument().also {
                container.db.documentDao().setFailure(it, """{"source":"TEXT","stage":"CONSOLIDATING","code":"UNKNOWN"}""")
            }
        }
        val stem = "${(scale*100).toInt()}-${if(dark) "dark" else "light"}-${if(rtl) "rtl" else "ltr"}"
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalContainer provides container, LocalDensity provides Density(density.density, scale), LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                CraminTheme(darkTheme = dark) {
                    Surface(Modifier.width(320.dp).fillMaxHeight()) { CraminNavHost(Routes.LIBRARY) }
                }
            }
        }
        awaitTag("libraryList")
        compose.onNodeWithTag("libraryList").performScrollToNode(hasTestTag("play-$id"))
        shot("$stem-library")
        compose.onNodeWithTag("play-$id").performScrollTo().performClick()
        awaitTag("category_CORE")
        compose.onNodeWithText(context.getString(pro.perfectproduct.cramin.R.string.diagnostic_recovered)).assertDoesNotExist()
        compose.onNodeWithText("Скопировать диагностику").assertDoesNotExist()
        compose.onNodeWithTag("category_CORE").performScrollTo().assertIsOn()
        shot("$stem-ready")
        compose.onNodeWithTag("onlyStarred").performScrollTo().performClick().assertIsOn()
        compose.onNodeWithTag("studyButton").performScrollTo().assertTextContains("1", substring = true)
        compose.onNodeWithTag("onlyStarred").performScrollTo().performClick()
        compose.onNodeWithTag("sessionOptions").performScrollTo().performClick()
        compose.onNodeWithText("Первым показывать").assertIsDisplayed()
        shot("$stem-options")
        compose.onNodeWithText(context.getString(pro.perfectproduct.cramin.R.string.action_ok)).performScrollTo().performClick()
        compose.onNodeWithTag("docMenu").performClick()
        compose.onNodeWithTag("diagnosticsMenuItem").performClick()
        compose.onNodeWithText("Скопировать диагностику").performScrollTo().assertIsDisplayed()
        shot("$stem-diagnostics")
        compose.onNodeWithContentDescription(context.getString(pro.perfectproduct.cramin.R.string.action_back)).performClick()
        compose.onNodeWithTag("tabText").performClick()
        compose.onNodeWithTag("textList").performScrollToNode(hasText("The bank was closed."))
        compose.onNodeWithText("The bank was closed.").assertIsDisplayed()
        shot("$stem-reader")
        compose.onNodeWithTag("tabCards").performClick()
        compose.onNodeWithTag("studyButton").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("autoplay").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("autoplay").assertIsDisplayed()
        compose.onNodeWithTag("undo").assertIsDisplayed()
        assertTrue("Undo stays physically left of play, including RTL", compose.onNodeWithTag("undo").fetchSemanticsNode().boundsInRoot.center.x < compose.onNodeWithTag("autoplay").fetchSemanticsNode().boundsInRoot.center.x)
        compose.onNodeWithTag("counter").assertTextContains("1 / 2")
        compose.onNodeWithText("Основные").assertDoesNotExist()
        shot("$stem-study-play")
        compose.onNodeWithTag("autoplay").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription(context.getString(pro.perfectproduct.cramin.R.string.study_pause)).fetchSemanticsNodes().isNotEmpty() }
        shot("$stem-study-pause")
        compose.onNodeWithTag("autoplay").performClick()
        compose.onNodeWithTag("studyClose").performClick()
        compose.onNodeWithContentDescription(context.getString(pro.perfectproduct.cramin.R.string.action_back)).performClick()
        compose.onNodeWithTag("libraryList").performScrollToNode(hasText("Материал без категорий"))
        compose.onNodeWithText("Материал без категорий").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText(context.getString(pro.perfectproduct.cramin.R.string.topic_determine)).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText(context.getString(pro.perfectproduct.cramin.R.string.topic_determine)).performScrollTo().assertIsDisplayed()
        shot("$stem-legacy")
        compose.onNodeWithText(context.getString(pro.perfectproduct.cramin.R.string.topic_determine)).performClick()
        compose.onNodeWithText(context.getString(pro.perfectproduct.cramin.R.string.topic_enrichment_confirm)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(pro.perfectproduct.cramin.R.string.action_cancel)).performClick()
        compose.onNodeWithContentDescription(context.getString(pro.perfectproduct.cramin.R.string.action_back)).performClick()
        compose.onNodeWithTag("libraryList").performScrollToNode(hasText("Ошибка импорта PDF"))
        compose.onNodeWithText("Ошибка импорта PDF").performClick()
        awaitTag("errorBanner")
        compose.onNodeWithTag("errorBanner").performScrollTo().assertIsDisplayed()
        shot("$stem-error")
        compose.onNodeWithContentDescription(context.getString(pro.perfectproduct.cramin.R.string.action_back)).performClick()
        compose.onNodeWithTag("libraryCreate").performClick()
        awaitTag("sourceText")
        compose.onNodeWithTag("sourceText").performScrollTo().assertIsDisplayed()
        shot("$stem-create")
        compose.onNodeWithTag("createClose").performClick()
        compose.onNodeWithTag("librarySettings").performClick()
        shot("$stem-settings-learning")
        compose.onNodeWithText("Обработка материалов ▾").performScrollTo().performClick()
        compose.onNodeWithTag("keyField").performScrollTo().assertIsDisplayed()
        shot("$stem-settings-processing")
        compose.onNodeWithText("Приложение ▾").performScrollTo().performClick()
        compose.onNodeWithTag("version").performScrollTo().assertIsDisplayed()
        shot("$stem-settings-app")
        assertTrue(container.fakeLlm.requests.isEmpty())
    }
    private fun awaitTag(tag: String) { compose.waitUntil(5000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 } }
    private fun shot(name: String) {
        compose.waitForIdle()
        val dir = File(context.getExternalFilesDir(null), "navigation-review").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try { File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
    }
}
