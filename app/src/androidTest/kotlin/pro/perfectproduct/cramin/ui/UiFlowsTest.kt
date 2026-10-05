package pro.perfectproduct.cramin.ui

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import pro.perfectproduct.cramin.app.CraminApp
import pro.perfectproduct.cramin.app.MainActivity
import pro.perfectproduct.cramin.data.db.CardEntity
import pro.perfectproduct.cramin.data.db.CardStatus
import pro.perfectproduct.cramin.data.db.CraminDatabase
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
                CardEntity(documentId = docId, lemmaKey = "${pair.first}|NOUN", meaningKey = pair.second, lemma = pair.first, lemmaVocalized = null, pos = Pos.NOUN, lang = "en", targetLang = "ru", status = CardStatus.NEW, starred = false, firstSentenceIdx = i, updatedAt = 0L),
            )
            val senseId = db.cardDao().insertSense(SenseEntity(cardId = cardId, idx = 0, translation = pair.second, exampleOccurrenceId = null))
            val occ = db.cardDao().insertOccurrence(OccurrenceEntity(cardId = cardId, senseId = senseId, sentenceId = sIds[0], surface = pair.first, targetSurface = null, start = 4, end = 8, targetStart = null, targetEnd = null, isExample = true))
            db.cardDao().setSenseExample(senseId, occ)
        }
        return docId
    }

    private fun openDiagnostics() {
        compose.onNodeWithTag("docMenu").performClick()
        compose.onNodeWithTag("diagnosticsMenuItem").performClick()
    }

    private fun copyStateAndWait() {
        if (compose.onAllNodes(hasTestTag("tabCards")).fetchSemanticsNodes().isNotEmpty()) openDiagnostics()
        compose.runOnIdle {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("test", ""))
        }
        compose.onNodeWithText("Скопировать диагностику").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            var copied = ""
            compose.runOnIdle {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
            }
            copied.contains("State snapshot copied with Cramin:")
        }
    }

    @Test fun legacyReadyShowsRecoveredHistoryWithoutRerunningWork() = runBlocking<Unit> {
        val id = seedDocument()
        val raw = """{"source":"TEXT","stage":"CONSOLIDATING","code":"UNKNOWN","observedAtEpochMs":1791059173074}"""
        container.db.documentDao().setFailure(id, raw)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("doc-$id")).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("doc-$id").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("docMenu")).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText(context.getString(pro.perfectproduct.cramin.R.string.diagnostic_recovered)).assertDoesNotExist()
        openDiagnostics()
        compose.onNodeWithText(context.getString(pro.perfectproduct.cramin.R.string.diagnostic_recovered)).assertIsDisplayed()
        compose.onNodeWithTag("errorBanner").assertDoesNotExist()
        copyStateAndWait()
        compose.runOnIdle {
            val copied = (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip!!.getItemAt(0).text.toString()
            org.junit.Assert.assertTrue(copied.contains("Document status: READY"))
            org.junit.Assert.assertTrue(copied.contains("RECOVERED_BY_DOCUMENT_READY"))
        }
        assertEquals(raw, container.documentRepository.get(id)!!.failureJson)
        assertEquals(0, container.fakeLlm.requests.size)
    }

    @Test fun existingCardsDuringProcessingDoNotShowRecovered() = runBlocking<Unit> {
        val id = seedDocument()
        container.db.documentDao().setFailure(id, """{"source":"TEXT","stage":"CONSOLIDATING","code":"UNKNOWN"}""")
        container.db.documentDao().setStatus(id, DocStatus.CONSOLIDATING, .9f, null, null, 3)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("doc-$id")).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("doc-$id").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("docMenu")).fetchSemanticsNodes().size == 1 }
        openDiagnostics()
        compose.onNodeWithText(context.getString(pro.perfectproduct.cramin.R.string.diagnostic_processing_state)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(pro.perfectproduct.cramin.R.string.diagnostic_recovered)).assertDoesNotExist()
    }

    @Test
    fun failedRowOpensReasonAndLanguageActionWithoutRetry() = runBlocking<Unit> {
        val id = seedDocument()
        container.db.documentDao().setStatus(id, DocStatus.FAILED, .1f, "SAME_LANGUAGE", "PRIVATE", 2)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("doc-$id")).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("doc-$id").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("docMenu")).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("errorBanner").assertIsDisplayed()
        compose.onNodeWithText("Сведения и диагностика").assertIsDisplayed()
        assertEquals(DocStatus.FAILED, container.documentRepository.get(id)?.status)
        compose.onNodeWithText(context.getString(pro.perfectproduct.cramin.R.string.action_choose_target_lang)).performClick()
        compose.onNodeWithText("Английский").assertIsDisplayed()
    }

    @Test
    fun oldFailureClipboardLabelsCopyTimeAndCannotInventRequest() = runBlocking<Unit> {
        val id = seedDocument()
        container.db.documentDao().setStatus(id, DocStatus.FAILED, .1f, "BAD_REQUEST", "PRIVATE", 2)
        container.db.documentDao().setFailure(id,
            """{"source":"TEXT","stage":"BRIEFING","code":"BAD_REQUEST"}""")
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("doc-$id")).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("doc-$id").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("docMenu")).fetchSemanticsNodes().size == 1 }
        copyStateAndWait()
        compose.runOnIdle {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val copied = clipboard.primaryClip!!.getItemAt(0).text.toString()
            org.junit.Assert.assertTrue(copied.contains("Copied with Cramin:"))
            org.junit.Assert.assertTrue(copied.contains("Request event: UNKNOWN (not recorded)"))
            org.junit.Assert.assertFalse(copied.contains("PRIVATE"))
        }
        assertEquals(DocStatus.FAILED, container.documentRepository.get(id)?.status)
    }

    @Test fun consolidationFailureOffersExplicitNetworkFreeRecovery() = runBlocking<Unit> {
        val id = seedDocument()
        val saved = container.documentRepository.get(id)!!
        container.db.documentDao().update(saved.copy(sourceType = SourceType.URL, sourceLang = "ru", targetLang = "en",
            modelsSnapshotJson = """{"brief":{"model":"synthetic-model"}}"""))
        container.db.openHelper.writableDatabase.execSQL("DELETE FROM Card WHERE documentId = ?", arrayOf(id))
        container.db.documentDao().setStatus(id, DocStatus.FAILED, .54f, "UNKNOWN", "PRIVATE", 2)
        container.db.documentDao().setFailure(id,
            """{"source":"URL","stage":"CONSOLIDATING","code":"UNKNOWN","observedAtEpochMs":1791027829315,"local":{"build":"0.1.26-debug","buildCode":26,"attemptId":"synthetic","cacheOnly":true,"step":"REQUEST","exceptionTypes":["pro.perfectproduct.cramin.pipeline.ConsolidationCacheMissing"],"appFrames":[],"counts":{},"clientInvocations":0,"clientResponses":0}}""")
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("doc-$id")).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("doc-$id").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("docMenu")).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("resumeConsolidationApi").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("retryLocalConsolidation").assertDoesNotExist()
        openDiagnostics()
        compose.onNodeWithTag("retryLocalConsolidation").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("resumeConsolidationApi").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(pro.perfectproduct.cramin.R.string.consolidation_api_confirm)).assertIsDisplayed()
        assertEquals(0, container.fakeLlm.requests.size)
        compose.onNodeWithText(context.getString(pro.perfectproduct.cramin.R.string.consolidation_api_cancel)).performClick()
        assertEquals(DocStatus.FAILED, container.documentRepository.get(id)?.status)
        copyStateAndWait()
        compose.runOnIdle {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val copied = clipboard.primaryClip!!.getItemAt(0).text.toString()
            org.junit.Assert.assertTrue(copied.contains("ConsolidationCacheMissing"))
            org.junit.Assert.assertTrue(copied.contains("NOT_INVOKED"))
        }
    }

    // Opt-in synthetic screenshots collected by Gradle; no document data from the owner.
    private fun captureDocumentActions(tab: String) {
        val args = androidx.test.platform.app.InstrumentationRegistry.getArguments()
        if (args.getString("captureDocumentActions") != "true") return
        val dir = args.getString("additionalTestOutputDir") ?: return
        // Some headless API26 images cannot capture a frame; UI assertions still run.
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        java.io.File(dir, "document-actions-$tab.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test
    fun libraryRowOpensStudyPreparation() = runBlocking<Unit> {
        val id = seedDocument()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        assertLibraryRowOpensStudyPreparation(id)
    }

    @Test
    fun libraryRowOpensStudyPreparationAfterDelayedRoomQuery() = runBlocking<Unit> {
        // Hold real Room queries after seeding: Compose can be idle while the library is empty.
        val queryGate = AtomicReference<CountDownLatch?>()
        val queryStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val timer = Executors.newSingleThreadScheduledExecutor()
        val gatedExecutor = Executor { task ->
            executor.execute {
                queryGate.get()?.let { gate ->
                    queryStarted.countDown()
                    check(gate.await(10, TimeUnit.SECONDS)) { "Room query gate timed out" }
                }
                task.run()
            }
        }
        try {
            container.db.close()
            container = TestContainer(context, databaseProvider = {
                androidx.room.Room.inMemoryDatabaseBuilder(context, CraminDatabase::class.java)
                    .allowMainThreadQueries()
                    .setQueryExecutor(gatedExecutor)
                    .build()
            })
            container.settingsStore.setOnboardingDone(true)
            app.container = container
            val id = seedDocument()
            queryGate.set(release)
            scenario = ActivityScenario.launch(MainActivity::class.java)
            compose.waitForIdle()
            org.junit.Assert.assertTrue("Room query must be held", queryStarted.await(5, TimeUnit.SECONDS))
            compose.onNodeWithText("Riverside Library").assertDoesNotExist()
            // Controlled external-work latency, not a sleep/retry of the UI assertion.
            timer.schedule({ release.countDown() }, 1, TimeUnit.SECONDS)
            assertLibraryRowOpensStudyPreparation(id)
        } finally {
            release.countDown()
            scenario?.close()
            scenario = null
            timer.shutdownNow()
            executor.shutdown()
        }
    }

    private fun assertLibraryRowOpensStudyPreparation(id: Long) {
        // Room's first emission is external work; Compose idle alone does not await it.
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("doc-$id")).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("Riverside Library").assertIsDisplayed()
        compose.onNodeWithTag("allUnlearned").assertIsDisplayed()
        compose.onNodeWithTag("doc-$id").performClick()
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

    private fun awaitAutoplayDescription(label: Int) {
        val description = context.getString(label)
        // Session changes commit to Room off the Compose clock before reaching the UI.
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag("autoplay") and hasContentDescription(description)).fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag("autoplay").assertContentDescriptionEquals(description)
    }

    @Test
    fun autoplayPausesOnCancelledPointerAndPauseButtonDoesNotRestart() = runBlocking<Unit> {
        val id = seedDocument()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("doc-$id")).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("doc-$id").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("studyButton")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("studyButton").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("flashCard")).fetchSemanticsNodes().isNotEmpty() }
        // Accessibility action has no preceding pointer-down.
        compose.onNodeWithTag("autoplay").performClick()
        awaitAutoplayDescription(pro.perfectproduct.cramin.R.string.study_pause)
        compose.onNodeWithTag("flashCard").performTouchInput { down(center); cancel() }
        awaitAutoplayDescription(pro.perfectproduct.cramin.R.string.study_autoplay)
        compose.onNodeWithTag("counter").assertTextContains("1 / 2")
        compose.onNodeWithTag("cardFront").assertIsDisplayed()
        // Real pointer clicks start then pause, even though root intercepts pointer-down.
        compose.onNodeWithTag("autoplay").performTouchInput { click() }
        awaitAutoplayDescription(pro.perfectproduct.cramin.R.string.study_pause)
        compose.onNodeWithTag("autoplay").performTouchInput { click() }
        awaitAutoplayDescription(pro.perfectproduct.cramin.R.string.study_autoplay)
        assertEquals(CardStatus.NEW, container.cardRepository.getStatus(cardId(id, "bank")))
    }

    @Test
    fun studySessionFlipSwipeUndoAndRoundEnd() = runBlocking<Unit> {
        val id = seedDocument()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("doc-$id")).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("doc-$id").performClick()
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
        // Room commits before the ViewModel publishes the new session counters.
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("knownCount") and hasText("1")).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("knownCount").assertTextContains("1")
        compose.onNodeWithTag("counter").assertTextContains("2 / 2")

        // Свайп влево → «ещё учу» и LEARNING; конец раунда.
        compose.onNodeWithTag("flashCard").performTouchInput { swipeLeft() }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("roundSummary")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(CardStatus.LEARNING, runBlocking { container.cardRepository.getStatus(cardId(id, "closed")) })
        compose.onNodeWithTag("roundSummary").assertTextContains("Вы знаете 1 из 2")
        compose.onNodeWithTag("repeatLearning").assertIsDisplayed()
        reviewShot("round-complete")

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
        // Room commits before the ViewModel publishes the new session counters.
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("knownCount") and hasText("1")).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("knownCount").assertTextContains("1")
        compose.onNodeWithTag("learningCount").assertTextContains("0")
    }

    @Test
    fun leavingSessionMidwayOffersResumeEvenAfterCardsBecameKnown() = runBlocking<Unit> {
        val id = seedDocument()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("doc-$id")).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("doc-$id").performClick()
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
        compose.onNodeWithTag("resume").assertTextContains("2/2", substring = true).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("counter")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("counter").assertTextContains("2 / 2")
    }

    @Test
    fun completedSharedMeaningSessionResumesWithExampleAndExactUndo() = runBlocking<Unit> {
        val banks = CardStatus.entries.map { status ->
            val doc = seedDocument()
            val bank = cardId(doc, "bank")
            container.cardRepository.setStatus(bank, status)
            container.cardRepository.setStatus(cardId(doc, "closed"), CardStatus.KNOWN)
            bank
        }
        val original = banks.associateWith { container.cardRepository.getStatus(it) }
        val before = container.cardRepository.sharedDeckCards(pro.perfectproduct.cramin.util.Lang.EN, pro.perfectproduct.cramin.util.Lang.RU).single()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("allUnlearned")).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("allUnlearned").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("allDeckStudy")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("allDeckStudy").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("flashCard")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("flashCard").performTouchInput { swipeRight() }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("roundSummary")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("roundDone").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("allDeckStudy")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("allDeckStudy").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("resume")).fetchSemanticsNodes().isNotEmpty() }
        reviewShot("resume")
        compose.onNodeWithTag("resume").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("roundUndo")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("roundUndo").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("flashCard")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(original, banks.associateWith { container.cardRepository.getStatus(it) })
        val after = container.cardRepository.sharedDeckCards(pro.perfectproduct.cramin.util.Lang.EN, pro.perfectproduct.cramin.util.Lang.RU).single()
        assertEquals(before.senses, after.senses)
        compose.onNodeWithTag("flashCard").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("cardBack")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("банк").assertIsDisplayed()
        compose.onNodeWithText("The bank was closed.").assertIsDisplayed()
    }

    private suspend fun cardId(docId: Long, lemma: String): Long = container.db.cardDao().getByDocument(docId).first { it.lemma == lemma }.id
    private fun reviewShot(name: String) {
        compose.waitForIdle()
        val dir = java.io.File(context.getExternalFilesDir(null), "navigation-review").apply { mkdirs() }
        // Some headless API26 images cannot capture a frame; UI assertions still run.
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        try { java.io.File(dir, "extra-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
    }

}
