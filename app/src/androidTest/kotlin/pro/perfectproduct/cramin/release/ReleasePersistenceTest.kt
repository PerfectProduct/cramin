package pro.perfectproduct.cramin.release

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import pro.perfectproduct.cramin.app.CraminApp
import pro.perfectproduct.cramin.app.MainActivity
import pro.perfectproduct.cramin.data.db.*
import pro.perfectproduct.cramin.study.SessionState
import pro.perfectproduct.cramin.study.UndoEntry
import pro.perfectproduct.cramin.study.UndoPosition
import pro.perfectproduct.cramin.study.UndoAction

/** Explicit adb instrumentation across two APK installs. Uses real persistent production storage. */
class ReleasePersistenceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app = ApplicationProvider.getApplicationContext<CraminApp>()
    private suspend fun seedDocument(): Long {
        val db = app.container.db
        val docId = db.documentDao().insert(
            DocumentEntity(
                title = "Release persistence fixture", emoji = "📚", sourceType = SourceType.TEXT, sourceRef = "", sourceLang = "en", targetLang = "ru",
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
                CardEntity(documentId = docId, lemmaKey = "${pair.first}|NOUN", meaningKey = pair.second, lemma = pair.first, lemmaVocalized = null, pos = Pos.NOUN, lang = "en", targetLang = "ru", status = CardStatus.NEW, starred = false, firstSentenceIdx = i, updatedAt = 0L),
            )
            val senseId = db.cardDao().insertSense(SenseEntity(cardId = cardId, idx = 0, translation = pair.second, exampleOccurrenceId = null))
            val occ = db.cardDao().insertOccurrence(OccurrenceEntity(cardId = cardId, senseId = senseId, sentenceId = sIds[0], surface = pair.first, targetSurface = null, start = 4, end = 8, targetStart = null, targetEnd = null, isExample = true))
            db.cardDao().setSenseExample(senseId, occ)
        }
        return docId
    }


    @Test fun persistenceAcrossInstall() = runBlocking<Unit> {
        val mode = InstrumentationRegistry.getArguments().getString("persistenceMode")
        org.junit.Assume.assumeTrue("Only explicit two-install persistence run", mode != null)
        val c = app.container
        if (mode == "seed") {
            assertTrue(c.db.documentDao().getAll().isEmpty())
            c.settingsStore.setOnboardingDone(true)
            c.settingsStore.setDefaultTargetLang("he")
            c.settingsStore.setTtsRate(1.25f)
            c.settingsStore.setAutoplayIntervals(12345, 23456)
            c.settingsStore.toggleAllCategory(4)
            val id = seedDocument()
            c.settingsStore.setDocumentShuffle(id, true)
            val cards = c.db.cardDao().getByDocument(id)
            c.cardRepository.setStatus(cards.first().id, CardStatus.KNOWN)
            c.cardRepository.setStarred(cards.first().id, true)
            val key = "release-persistence"
            c.studyRepository.save(key, SessionState(key, cards.map { it.id }, position=1, knownThisRound=1,
                undoStack=listOf(UndoEntry(cards.first().id, CardStatus.NEW, UndoAction.KNOWN,
                    previousStatuses=mapOf(cards.first().id to CardStatus.NEW), before=UndoPosition(1,0,0,0)))).toJson())
        }
        val doc = c.db.documentDao().getAll().single { it.title == "Release persistence fixture" }
        assertEquals(DocStatus.READY, doc.status)
        val settings = c.settingsStore.current()
        assertTrue(settings.onboardingDone)
        assertEquals("he", settings.defaultTargetLang)
        assertEquals(1.25f, settings.ttsRate, 0f)
        assertEquals(12345, settings.autoplayFrontMs)
        assertEquals(23456, settings.autoplayBackMs)
        assertTrue(c.settingsStore.documentShuffle(doc.id).first())
        val cards = c.db.cardDao().getByDocument(doc.id)
        assertEquals(2, cards.size)
        assertEquals(CardStatus.KNOWN, cards.first().status)
        assertTrue(cards.first().starred)
        val state = SessionState.fromJson(c.studyRepository.load("release-persistence")!!)!!
        assertEquals(1, state.position)
        assertEquals(1, state.knownThisRound)
        assertEquals(3, c.settingsStore.allCategoryMask.first())
        assertTrue(state.canUndo)
        assertEquals(cards.map { it.id }, state.order)
        assertEquals("The bank was closed.", c.db.sentenceDao().getByDocument(doc.id).single().text)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10000) { compose.onAllNodesWithTag("doc-${doc.id}").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("doc-${doc.id}").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("studyButton").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("studyButton").assertIsDisplayed()
        }
    }
}
