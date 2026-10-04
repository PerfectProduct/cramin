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
    @Test fun wide100() = review(1f, true, false, 412)
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


    private fun review(scale: Float, dark: Boolean, rtl: Boolean, width: Int = 320) {
        val id = runBlocking {
            container.settingsStore.setOnboardingDone(true)
            container.settingsStore.setAutoplayIntervals(10000, 10000)
            seedDocument(legacy = true)
            seedDocument(failed = true)
            seedDocument().also {
                container.db.documentDao().setFailure(it, """{"source":"TEXT","stage":"CONSOLIDATING","code":"UNKNOWN"}""")
            }
        }
        val stem = "${width}-${(scale*100).toInt()}-${if(dark) "dark" else "light"}-${if(rtl) "rtl" else "ltr"}"
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalContainer provides container, LocalDensity provides Density(density.density, scale), LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                CraminTheme(darkTheme = dark) {
                    Surface(Modifier.width(width.dp).fillMaxHeight()) { CraminNavHost(Routes.LIBRARY) }
                }
            }
        }
        awaitTag("libraryList")
        compose.onNodeWithTag("libraryList").performScrollToNode(hasTestTag("doc-$id"))
        shot("$stem-library")
        compose.onNodeWithTag("doc-$id").performScrollTo().performClick()
        awaitTag("category_CORE")
        compose.onNodeWithText(context.getString(pro.perfectproduct.cramin.R.string.diagnostic_recovered)).assertDoesNotExist()
        compose.onNodeWithText("Скопировать диагностику").assertDoesNotExist()
        compose.onNodeWithTag("studyButton").assertIsDisplayed()
        shot("$stem-ready")
        compose.onNodeWithTag("category_CORE").performScrollTo().assertIsOn()
        compose.onNodeWithTag("onlyStarred").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("onlyStarred") and isOn()).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("onlyStarred").assertIsOn()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("studyButton") and hasText("1", substring = true)).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("studyButton").assertTextContains("1", substring = true)
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
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText("The bank was closed.").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val bankBounds = layouts.single().getBoundingBox(5)
        compose.onNodeWithText("The bank was closed.").performTouchInput { click(bankBounds.center) }
        compose.waitUntil(5000) { compose.onAllNodesWithText(context.getString(pro.perfectproduct.cramin.R.string.word_status_new)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(pro.perfectproduct.cramin.R.string.word_status_new)).performScrollTo().assertIsSelected()
        shot("$stem-word-sheet")
        compose.onNodeWithText(context.getString(pro.perfectproduct.cramin.R.string.word_status_known)).performScrollTo().performClick()
        val bankId = runBlocking { container.db.cardDao().getByDocument(id).first { it.lemma == "bank" }.id }
        compose.waitUntil(5000) { runBlocking { container.cardRepository.getStatus(bankId) } == CardStatus.KNOWN }
        compose.onNodeWithText(context.getString(pro.perfectproduct.cramin.R.string.word_status_new)).performScrollTo().performClick()
        compose.waitUntil(5000) { runBlocking { container.cardRepository.getStatus(bankId) } == CardStatus.NEW }
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag("tabCards").performClick()
        compose.onNodeWithTag("studyButton").performClick()
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
        compose.onNodeWithContentDescription(context.getString(pro.perfectproduct.cramin.R.string.study_settings)).performClick()
        shot("$stem-study-options")
        androidx.test.espresso.Espresso.pressBack()
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
        compose.onNodeWithTag("sourceUrl").performClick()
        compose.onNodeWithTag("urlField").performScrollTo().assertIsDisplayed()
        shot("$stem-create-link")
        compose.onNodeWithTag("sourcePdf").performScrollTo().performClick()
        compose.onNodeWithTag("pickPdf").assertIsDisplayed()
        compose.onNodeWithTag("submitPdf").assertIsNotEnabled()
        shot("$stem-create-pdf")
        assertTrue(container.fakeLlm.requests.isEmpty())
        compose.onNodeWithTag("createClose").performClick()
        compose.onNodeWithTag("librarySettings").performClick()
        shot("$stem-settings-home")
        compose.onNodeWithTag("settingsAudio").performScrollTo().performClick()
        shot("$stem-settings-audio")
        compose.onNodeWithContentDescription(context.getString(pro.perfectproduct.cramin.R.string.action_back)).performClick()
        compose.onNodeWithTag("settingsProcessing").performScrollTo().performClick()
        compose.onNodeWithTag("keyField").performScrollTo().assertIsDisplayed()
        shot("$stem-settings-processing")
        compose.onNodeWithContentDescription(context.getString(pro.perfectproduct.cramin.R.string.action_back)).performClick()
        compose.onNodeWithTag("settingsApp").performScrollTo().performClick()
        compose.onNodeWithTag("version").assertIsDisplayed()
        shot("$stem-settings-app")
        assertTrue(container.fakeLlm.requests.isEmpty())
    }
    @Test fun secondaryRoutesAndLargeCounts() {
        val id = runBlocking {
            container.settingsStore.setOnboardingDone(true)
            seedDocument().also { id ->
                val doc = container.db.documentDao().getById(id) ?: error("missing synthetic document")
                container.db.documentDao().update(doc.copy(title = "Линейные модели в машинном обучении", sourceType = SourceType.URL))
                repeat(1547) { i -> container.db.cardDao().insertCard(CardEntity(documentId = id, lemmaKey = "word$i|NOUN", meaningKey = "meaning$i", lemma = "word$i", lemmaVocalized = null, pos = Pos.NOUN, lang = "en", targetLang = "ru", status = if (i < 213) CardStatus.KNOWN else CardStatus.NEW, starred = i < 12, category = TopicCategory.entries[i % 3], firstSentenceIdx = i + 2, updatedAt = 0L)) }
            }
        }
        lateinit var nav: androidx.navigation.NavHostController
        compose.setContent { CompositionLocalProvider(LocalContainer provides container) { CraminTheme(darkTheme = true) {
            nav = androidx.navigation.compose.rememberNavController()
            Surface(Modifier.fillMaxSize()) { CraminNavHost(Routes.LIBRARY, nav) }
        } } }
        awaitTag("libraryList"); shot("extra-library-large-counts")
        compose.onNodeWithContentDescription(context.getString(pro.perfectproduct.cramin.R.string.library_search)).performClick()
        compose.onNodeWithTag("librarySearch").performTextInput("не найдено")
        shot("extra-search-empty")
        compose.onNodeWithContentDescription(context.getString(pro.perfectproduct.cramin.R.string.library_search)).performClick()
        compose.onNodeWithTag("doc-$id").performTouchInput { longClick() }; shot("extra-library-menu")
        compose.onNodeWithText(context.getString(pro.perfectproduct.cramin.R.string.action_rename)).performClick(); shot("extra-rename")
        compose.onNodeWithText(context.getString(pro.perfectproduct.cramin.R.string.action_cancel)).performClick()
        compose.onNodeWithTag("doc-$id").performClick(); awaitTag("studyButton"); shot("extra-ready-large-counts")
        compose.onNodeWithTag("docMenu").performClick(); shot("extra-document-menu")
        compose.onNodeWithText(context.getString(pro.perfectproduct.cramin.R.string.action_reprocess)).performClick(); shot("extra-reprocess-confirm")
        compose.onNodeWithText(context.getString(pro.perfectproduct.cramin.R.string.action_cancel)).performClick()
        compose.onNodeWithTag("docMenu").performClick()
        compose.onNodeWithText(context.getString(pro.perfectproduct.cramin.R.string.action_delete)).performClick(); shot("extra-delete-confirm")
        compose.onNodeWithText(context.getString(pro.perfectproduct.cramin.R.string.action_cancel)).performClick()
        fun go(route: String) { compose.runOnUiThread { nav.navigate(route) }; compose.waitForIdle() }
        go(Routes.ALL_DECK); awaitTag("allDeckStudy"); shot("extra-all-deck")
        compose.onNodeWithTag("allDeckOptions").performScrollTo().performClick(); compose.onNodeWithTag("directionToggle").assertIsDisplayed(); shot("extra-all-deck-options")
        compose.onNodeWithText(context.getString(pro.perfectproduct.cramin.R.string.action_ok)).performScrollTo().performClick()
        go(Routes.SETTINGS); compose.onNodeWithTag("settingsAppearance").performClick(); shot("extra-appearance")
        compose.onNodeWithContentDescription(context.getString(pro.perfectproduct.cramin.R.string.action_back)).performClick()
        compose.onNodeWithTag("settingsDefaults").performClick(); shot("extra-defaults")
        go(Routes.LICENSES); shot("extra-licenses")
        go(Routes.modelPicker("stt")); shot("extra-model-picker")
        go(Routes.ONBOARDING); shot("extra-onboarding")
        compose.onNodeWithTag("onboardingStart").assertIsDisplayed(); shot("extra-onboarding-bottom")
        assertTrue(container.fakeLlm.requests.isEmpty())
    }
    private fun awaitTag(tag: String) { compose.waitUntil(5000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 } }
    private fun shot(name: String) {
        compose.waitForIdle()
        // UiAutomation captures the compositor, which can lag Compose semantics by a frame.
        Thread.sleep(250)
        compose.waitForIdle()
        val dir = File(context.getExternalFilesDir(null), "navigation-review").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try { File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
    }
}
