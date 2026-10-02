package pro.perfectproduct.cramin.ui

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pro.perfectproduct.cramin.app.CraminApp
import pro.perfectproduct.cramin.app.MainActivity
import pro.perfectproduct.cramin.data.db.CardEntity
import pro.perfectproduct.cramin.data.db.CardStatus
import pro.perfectproduct.cramin.data.db.DocStatus
import pro.perfectproduct.cramin.data.db.DocumentEntity
import pro.perfectproduct.cramin.data.db.OccurrenceEntity
import pro.perfectproduct.cramin.data.db.Pos
import pro.perfectproduct.cramin.data.db.SegmentEntity
import pro.perfectproduct.cramin.data.db.SenseEntity
import pro.perfectproduct.cramin.data.db.SentenceEntity
import pro.perfectproduct.cramin.data.db.SourceType
import pro.perfectproduct.cramin.testing.TestContainer

/** Инструментированные UI-тесты (SPEC §14.2): библиотека, share-интент, сессия с фейковым репозиторием. */
@RunWith(AndroidJUnit4::class)
class UiFlowsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app: CraminApp get() = context.applicationContext as CraminApp
    private lateinit var container: TestContainer
    private lateinit var original: pro.perfectproduct.cramin.app.AppContainer
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() = runBlocking<Unit> {
        original = app.container
        container = TestContainer(context)
        container.settingsStore.setOnboardingDone(true)
        app.container = container
    }

    @After
    fun tearDown() {
        scenario?.close()
        app.container = original
        container.db.close()
    }

    private suspend fun seedDocument(): Long {
        val db = container.db
        val docId = db.documentDao().insert(
            DocumentEntity(
                title = "Riverside Library", emoji = "📚", sourceType = SourceType.TEXT, sourceRef = "", sourceLang = "en", targetLang = "ru",
                status = DocStatus.READY, progress = 1f, errorCode = null, errorMessage = null, direction = null, pipelineVersion = 1,
                briefJson = null, modelsSnapshotJson = null, promptTokens = 1, completionTokens = 1, costUsd = 0.01, audioSeconds = 0,
                wordCount = 10, createdAt = 1L, updatedAt = 1L,
            ),
        )
        val sIds = db.sentenceDao().insertAll(listOf(SentenceEntity(documentId = docId, idx = 0, paragraphIdx = 0, text = "The bank was closed.", segmentId = null)))
        val seg = db.segmentDao().insert(SegmentEntity(documentId = docId, firstSentenceIdx = 0, lastSentenceIdx = 0, translation = "Банк был закрыт."))
        db.sentenceDao().assignSegment(docId, 0, 0, seg)
        for ((i, pair) in listOf("bank" to "банк", "closed" to "закрытый").withIndex()) {
            val cardId = db.cardDao().insertCard(
                CardEntity(documentId = docId, lemmaKey = "${pair.first}|NOUN", lemma = pair.first, lemmaVocalized = null, pos = Pos.NOUN, lang = "en", targetLang = "ru", status = CardStatus.NEW, starred = false, firstSentenceIdx = i, updatedAt = 0L),
            )
            val senseId = db.cardDao().insertSense(SenseEntity(cardId = cardId, idx = 0, translation = pair.second, exampleOccurrenceId = null))
            val occ = db.cardDao().insertOccurrence(OccurrenceEntity(cardId = cardId, senseId = senseId, sentenceId = sIds[0], surface = pair.first, targetSurface = null, start = 4, end = 8, targetStart = null, targetEnd = null, isExample = true))
            db.cardDao().setSenseExample(senseId, occ)
        }
        return docId
    }

    @Test
    fun libraryShowsDocumentAndPlayOpensCardsTab() = runBlocking<Unit> {
        val id = seedDocument()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithText("Riverside Library").assertIsDisplayed()
        compose.onNodeWithTag("allUnlearned").assertIsDisplayed()
        compose.onNodeWithTag("play-$id").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("studyButton")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("studyButton").assertIsDisplayed()
    }

    @Test
    fun shareIntentOpensCreateWithPrefilledUrl() {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "https://example.com/article")
        }
        scenario = ActivityScenario.launch(intent)
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("urlField")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("urlField").assertTextContains("https://example.com/article")
    }

    @Test
    fun studySessionFlipSwipeUndoAndRoundEnd() = runBlocking<Unit> {
        val id = seedDocument()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithTag("play-$id").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("studyButton")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("studyButton").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("flashCard")).fetchSemanticsNodes().isNotEmpty() }

        // Тап переворачивает.
        compose.onNodeWithTag("cardFront").assertIsDisplayed()
        compose.onNodeWithTag("flashCard").performClick()
        compose.waitUntil(3_000) { compose.onAllNodes(hasTestTag("cardBack")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("банк").assertIsDisplayed()

        // Свайп вправо → «знаю» и KNOWN в БД.
        compose.onNodeWithTag("flashCard").performTouchInput { swipeRight() }
        compose.waitUntil(5_000) { runBlocking { container.cardRepository.getStatus(cardId(id, "bank")) } == CardStatus.KNOWN }
        compose.onNodeWithTag("knownCount").assertTextContains("1")
        compose.onNodeWithTag("counter").assertTextContains("2 / 2")

        // Свайп влево → «ещё учу» и LEARNING; конец раунда.
        compose.onNodeWithTag("flashCard").performTouchInput { swipeLeft() }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("roundSummary")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(CardStatus.LEARNING, runBlocking { container.cardRepository.getStatus(cardId(id, "closed")) })
        compose.onNodeWithTag("roundSummary").assertTextContains("Вы знаете 1 из 2")
        compose.onNodeWithTag("repeatLearning").assertIsDisplayed()

        // Повтор невыученных: новый раунд из одной карточки; ↶ возвращает после свайпа.
        compose.onNodeWithTag("repeatLearning").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("flashCard")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("counter").assertTextContains("1 / 1")
        compose.onNodeWithTag("flashCard").performTouchInput { swipeRight() }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("roundSummary")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(CardStatus.KNOWN, runBlocking { container.cardRepository.getStatus(cardId(id, "closed")) })
        compose.onNodeWithTag("roundUndo").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("flashCard")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(CardStatus.LEARNING, container.cardRepository.getStatus(cardId(id, "closed")))
        compose.onNodeWithTag("counter").assertTextContains("1 / 1")
        compose.onNodeWithTag("undo").performClick()
        compose.waitUntil(5_000) { runBlocking { container.cardRepository.getStatus(cardId(id, "closed")) } == CardStatus.NEW }
        compose.onNodeWithTag("counter").assertTextContains("2 / 2")
        compose.onNodeWithTag("knownCount").assertTextContains("1")
        compose.onNodeWithTag("learningCount").assertTextContains("0")
    }

    @Test
    fun leavingSessionMidwayOffersResumeEvenAfterCardsBecameKnown() = runBlocking<Unit> {
        val id = seedDocument()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithTag("play-$id").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("studyButton")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("studyButton").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("flashCard")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("flashCard").performTouchInput { swipeRight() }
        compose.waitUntil(5_000) { runBlocking { container.cardRepository.getStatus(cardId(id, "bank")) } == CardStatus.KNOWN }
        compose.onNodeWithTag("studyClose").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("studyButton")).fetchSemanticsNodes().isNotEmpty() }
        // Колода «невыученные» теперь из одной карточки, но сессия продолжается со своих 2 (SPEC §9.6).
        compose.onNodeWithTag("studyButton").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("resume")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("resume").assertTextContains("1/2", substring = true)
        compose.onNodeWithTag("resume").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("counter")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("counter").assertTextContains("2 / 2")
    }

    @Test
    fun completedSharedSessionResumesWithMergedExamplesAndExactUndo() = runBlocking<Unit> {
        val banks = CardStatus.entries.mapIndexed { i, status ->
            val doc = seedDocument()
            val bank = cardId(doc, "bank")
            container.cardRepository.setStatus(bank, status)
            container.cardRepository.setStatus(cardId(doc, "closed"), CardStatus.KNOWN)
            container.db.openHelper.writableDatabase.execSQL("UPDATE Sense SET translation = ? WHERE cardId = ?", arrayOf<Any>("смысл $i", bank))
            bank
        }
        val original = banks.associateWith { container.cardRepository.getStatus(it) }
        val before = container.cardRepository.sharedDeckCards(pro.perfectproduct.cramin.util.Lang.EN, pro.perfectproduct.cramin.util.Lang.RU).single()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithTag("allUnlearned").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("allDeckStudy")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("allDeckStudy").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("flashCard")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("flashCard").performTouchInput { swipeRight() }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("roundSummary")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("roundDone").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("allDeckStudy")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("allDeckStudy").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("resume")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("resume").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("roundUndo")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("roundUndo").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("flashCard")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(original, banks.associateWith { container.cardRepository.getStatus(it) })
        val after = container.cardRepository.sharedDeckCards(pro.perfectproduct.cramin.util.Lang.EN, pro.perfectproduct.cramin.util.Lang.RU).single()
        assertEquals(before.senses, after.senses)
        compose.onNodeWithTag("flashCard").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("cardBack")).fetchSemanticsNodes().isNotEmpty() }
        repeat(3) { compose.onNodeWithText("смысл $it").assertIsDisplayed() }
    }

    private suspend fun cardId(docId: Long, lemma: String): Long = container.db.cardDao().getByDocument(docId).first { it.lemma == lemma }.id
}
