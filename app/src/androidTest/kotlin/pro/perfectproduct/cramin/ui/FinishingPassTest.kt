package pro.perfectproduct.cramin.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.*
import org.junit.Assert.*
import pro.perfectproduct.cramin.R
import pro.perfectproduct.cramin.app.*
import pro.perfectproduct.cramin.app.theme.CraminTheme
import pro.perfectproduct.cramin.data.db.*
import pro.perfectproduct.cramin.data.repo.*
import pro.perfectproduct.cramin.testing.*
import pro.perfectproduct.cramin.ui.document.*
import pro.perfectproduct.cramin.util.Lang
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Only the four revised areas. System font/width are supplied by the emulator, including dialog windows. */
class FinishingPassTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir = File(context.cacheDir, "finish-${System.nanoTime()}").apply { mkdirs() }
    private val keyChecks = AtomicInteger()
    private val onboardingWriteGate = AtomicReference<CompletableDeferred<Unit>?>()
    private val onboardingWriteStarted = CompletableDeferred<Unit>()
    private val fake = FakeLlmClient()
    private val container = object : AppContainer(context,
        databaseProvider = { CraminDatabase.inMemory(context) },
        settingsDataStoreProvider = { scope ->
            val store = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "settings.preferences_pb") }
            object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
                override val data = store.data
                override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences) =
                    store.updateData { current ->
                        val updated = transform(current)
                        val done = androidx.datastore.preferences.core.booleanPreferencesKey("onboarding_done")
                        if (current[done] != true && updated[done] == true) {
                            onboardingWriteGate.get()?.let { gate ->
                                onboardingWriteStarted.complete(Unit)
                                gate.await()
                            }
                        }
                        updated
                    }
            }
        },
        secretsDataStoreProvider = { scope -> PreferenceDataStoreFactory.create(scope = scope) { File(dir, "secrets.preferences_pb") } },
        secretCipher = PlainCipher()) {
        override val llmClient get() = fake
        override val httpClient = OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().url.encodedPath != "/api/v1/key") throw IOException("Network disabled in finishing test")
            keyChecks.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"data":{"limit_remaining":12.5}}""".toResponseBody()).build()
        }.build()
    }
    @After fun close() { container.db.close() }
    private fun str(id: Int) = context.getString(id)
    private fun awaitTag(tag: String) = compose.waitUntil(5000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
    private fun shot(name: String) {
        compose.waitForIdle(); Thread.sleep(300); compose.waitForIdle()
        val scale = (context.resources.configuration.fontScale * 100).toInt()
        val out = File(context.getExternalFilesDir(null), "finishing-review").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try { File(out, "$scale-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
    }
    private suspend fun seed(title: String, partial: Boolean = false): Long {
        val id = container.db.documentDao().insert(DocumentEntity(title=title, emoji="📚", sourceType=SourceType.TEXT, sourceRef="", sourceLang="en", targetLang="ru", status=DocStatus.READY,
            progress=1f, errorCode=null,errorMessage=null,direction=null,pipelineVersion=1,briefJson=null,modelsSnapshotJson=null,promptTokens=0,completionTokens=0,costUsd=null,audioSeconds=0,wordCount=20,createdAt=1,updatedAt=1))
        repeat(2) { i -> container.db.cardDao().insertCard(CardEntity(documentId=id,lemmaKey="word$i",meaningKey="meaning$i",lemma="word$i",lemmaVocalized=null,pos=Pos.NOUN,lang="en",targetLang="ru",status=CardStatus.NEW,starred=i==0,firstSentenceIdx=i,updatedAt=1,category=if(partial && i==0) TopicCategory.CORE else null)) }
        container.db.documentDao().setCategoryMask(id, if(partial) 1 else 3)
        return id
    }

    @Test fun onboardingWaitsForDurableSettingsBeforeLeaving() {
        val release = CompletableDeferred<Unit>()
        onboardingWriteGate.set(release)
        try {
            compose.setContent {
                CompositionLocalProvider(LocalContainer provides container) {
                    CraminTheme { Surface(Modifier.fillMaxSize()) { CraminNavHost(Routes.ONBOARDING) } }
                }
            }
            awaitTag("onboardingStart")
            compose.onNodeWithTag("onboardingStart").performClick()
            compose.waitUntil(5000) {
                onboardingWriteStarted.isCompleted || compose.onAllNodesWithTag("libraryCreate").fetchSemanticsNodes().isNotEmpty()
            }
            // Removing the onboarding entry must not cancel its still-pending settings write.
            compose.onNodeWithTag("libraryCreate").assertDoesNotExist()
            compose.onNodeWithTag("onboardingStart").assertIsDisplayed()
            assertTrue("The real DataStore update must be pending", onboardingWriteStarted.isCompleted)
            assertFalse(runBlocking { container.settingsStore.current().onboardingDone })
            release.complete(Unit)
            awaitTag("libraryCreate")
            assertTrue(runBlocking { container.settingsStore.current().onboardingDone })
            assertTrue(fake.requests.isEmpty())
            assertEquals(0, keyChecks.get())
        } finally {
            release.complete(Unit)
            onboardingWriteGate.set(null)
        }
    }

    @Test fun affectedNavigationAndSelectionStayOffline() {
        val (legacy, partial) = runBlocking { seed("Материал без категорий") to seed("Частично определённые категории", true) }
        lateinit var nav: androidx.navigation.NavHostController
        compose.setContent { CompositionLocalProvider(LocalContainer provides container) { CraminTheme(darkTheme = context.resources.configuration.fontScale != 1.5f) {
            nav = androidx.navigation.compose.rememberNavController()
            Surface(Modifier.fillMaxSize()) { CraminNavHost(Routes.ONBOARDING, nav) }
        } } }
        awaitTag("onboardingStart")
        compose.onNodeWithTag("onboardingStart").assertIsDisplayed()
        compose.onNodeWithText(str(R.string.onboarding_models_hint)).assertDoesNotExist()
        shot("onboarding")
        compose.onNodeWithTag("onboardingAdvanced").performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.onboarding_models_hint)).performScrollTo().assertIsDisplayed()
        shot("onboarding-advanced")
        compose.onNodeWithTag("onboardingAdvanced").performScrollTo().performClick()
        compose.onNodeWithTag("onboardingStart").performClick()
        awaitTag("libraryCreate")
        assertTrue(runBlocking { container.settingsStore.settings.first().onboardingDone })
        fun go(route: String) { compose.runOnUiThread { nav.navigate(route) }; compose.waitForIdle() }
        go(Routes.SETTINGS); compose.onNodeWithTag("settingsDefaults").performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.settings_default_direction)).performScrollTo().assertIsDisplayed()
        shot("defaults")
        compose.onNodeWithText(str(R.string.settings_direction_tgt)).performScrollTo().performClick()
        compose.waitUntil(5000) { runBlocking { container.settingsStore.settings.first().defaultDirection } == Direction.TGT_FRONT }
        assertEquals("ru", runBlocking { container.settingsStore.settings.first().defaultTargetLang })
        compose.onNodeWithContentDescription(str(R.string.action_back)).performClick()
        compose.onNodeWithTag("settingsDefaults").performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.settings_direction_tgt)).performScrollTo().assertIsSelected()
        go(Routes.document(legacy)); awaitTag("noCategories")
        compose.onNodeWithTag("category_CORE").assertDoesNotExist()
        compose.onNodeWithTag("studyButton").assertIsNotEnabled()
        assertEquals(3, runBlocking { container.db.documentDao().getById(legacy)?.categoryMask })
        shot("legacy-saved-subset")
        compose.onNodeWithTag("openFullCategorySet").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("studyButton") and isEnabled()).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("studyButton").assertTextContains("2", substring=true)
        // Reopen so this is an ordinary initial viewport, not the previous button's scroll position.
        go(Routes.LIBRARY); go(Routes.document(legacy)); awaitTag("noCategories"); shot("legacy")
        compose.onNodeWithTag("enrichCategories").performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.topic_enrichment_confirm)).assertExists()
        compose.onNodeWithText(str(R.string.action_cancel)).performClick()
        assertTrue(runBlocking { container.db.cardDao().getByDocument(legacy).all { it.category == null && it.status == CardStatus.NEW } })
        go(Routes.document(partial)); awaitTag("unclassifiedCount")
        compose.onNodeWithTag("unclassifiedCount").performScrollTo().assertTextContains("1", substring=true)
        compose.onNodeWithTag("studyButton").assertTextContains("1", substring=true)
        shot("partial")
        compose.onNodeWithTag("openFullCategorySet").performScrollTo().performClick()
        compose.waitUntil(5000) { runBlocking { container.db.documentDao().getById(partial)?.categoryMask } == 7 }
        compose.onNodeWithTag("onlyStarred").performScrollTo().performClick()
        compose.waitUntil(5000) { runBlocking { container.settingsStore.documentFilter(partial).first() } == DeckFilter.UNLEARNED_STARRED }
        go(Routes.LIBRARY); go(Routes.document(partial)); awaitTag("unclassifiedCount")
        compose.onNodeWithTag("onlyStarred").performScrollTo().assertIsOn()
        compose.onNodeWithTag("studyButton").assertTextContains("1", substring=true)
        assertEquals(7, runBlocking { container.db.documentDao().getById(partial)?.categoryMask })
        // First pass above uses the real "skip without key" path. Verify saving/checking separately,
        // so the system keyboard from synthetic input does not obscure subsequent screen captures.
        go(Routes.ONBOARDING)
        compose.onNodeWithTag("keyField").performScrollTo().performTextInput("synthetic-finishing-key")
        compose.onNodeWithTag("keyCheck").performScrollTo().performClick()
        compose.waitUntil(5000) { keyChecks.get() == 1 && compose.onAllNodes(hasTestTag("keyResult") and hasText("Ключ работает", substring=true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("keyResult").performScrollTo().assertTextContains("Ключ", substring=true)
        shot("key-result")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("onboardingStart").performClick()
        awaitTag("libraryCreate")
        assertTrue(fake.requests.isEmpty()); assertEquals(1, keyChecks.get())
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun wordPanelKeepsFullWidthTextAndActions() {
        val id = runBlocking { seed("Слова в контексте") }
        val base = runBlocking { container.db.cardDao().getByDocument(id).first() }
        var sample by mutableStateOf(StudyCard(base.id,id,"bank","bank",null,Pos.NOUN,Lang.EN,Lang.RU,CardStatus.NEW,true,0,listOf(StudySense(1,"банк",null))))
        var open by mutableStateOf(true)
        compose.setContent { CraminTheme(darkTheme = context.resources.configuration.fontScale != 1.5f) { Surface(Modifier.fillMaxSize()) {
            Text("Слова в контексте")
            if(open) ModalBottomSheet(onDismissRequest={open=false}) {
                WordSheet(sample, remember { DocumentViewModel(container,id) }, { sample=it })
            }
        } } }
        awaitTag("wordPartOfSpeech")
        val layouts=mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("wordPartOfSpeech").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse("Part of speech must not lose text", layouts.single().hasVisualOverflow)
        assertEquals("Full-width Russian noun fits on 320dp even at 200%", 1, layouts.single().lineCount)
        shot("word")
        compose.onNodeWithContentDescription(str(R.string.word_star)).performScrollTo().performClick()
        compose.waitUntil(5000) { runBlocking { !container.db.cardDao().getByDocument(id).first().starred } }
        compose.onNodeWithText(str(R.string.word_status_known)).performScrollTo().performClick()
        compose.waitUntil(5000) { runBlocking { container.cardRepository.getStatus(base.id) } == CardStatus.KNOWN }
        compose.runOnIdle { open=false }; compose.waitForIdle()
        compose.runOnIdle { sample=sample.copy(lemma="достопримечательность",lang=Lang.RU);open=true }
        awaitTag("wordPartOfSpeech");shot("word-long-russian")
        compose.onNodeWithText(str(R.string.word_status_new)).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { open=false }; compose.waitForIdle()
        compose.runOnIdle { sample=sample.copy(lemma="אינטרנציונליזציה",lang=Lang.HE,lemmaVocalized=null);open=true }
        awaitTag("wordPartOfSpeech");shot("word-hebrew")
        compose.onNodeWithText(str(R.string.word_status_new)).performScrollTo().assertIsDisplayed()
        assertTrue(fake.requests.isEmpty())
    }
}
