package pro.perfectproduct.cramin.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import pro.perfectproduct.cramin.R
import pro.perfectproduct.cramin.app.*
import pro.perfectproduct.cramin.app.theme.CraminTheme
import pro.perfectproduct.cramin.data.db.*
import pro.perfectproduct.cramin.data.repo.*
import pro.perfectproduct.cramin.testing.*
import pro.perfectproduct.cramin.ui.document.*
import pro.perfectproduct.cramin.ui.study.FlashCard
import pro.perfectproduct.cramin.util.Lang
import java.io.File

class AcceptedUiPolishTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dark = InstrumentationRegistry.getArguments().getString("dark") != "false"
    private val dir = File(context.cacheDir, "polish-${System.nanoTime()}").apply { mkdirs() }
    private val container = object : AppContainer(context,
        databaseProvider = { CraminDatabase.inMemory(context) },
        settingsDataStoreProvider = { scope -> PreferenceDataStoreFactory.create(scope = scope) { File(dir, "settings.preferences_pb") } },
        secretsDataStoreProvider = { scope -> PreferenceDataStoreFactory.create(scope = scope) { File(dir, "secrets.preferences_pb") } },
        secretCipher = PlainCipher()) {
        override val llmClient = FakeLlmClient()
        override val httpClient = okhttp3.OkHttpClient.Builder().addInterceptor { throw java.io.IOException("Network disabled") }.build()
    }
    @After fun close() { container.db.close() }
    private fun str(id: Int) = context.getString(id)
    private fun shot(name: String) {
        compose.waitForIdle()
        // Capture only: allow SurfaceFlinger to present the already asserted Compose frame.
        Thread.sleep(350)
        val out = File(context.getExternalFilesDir(null), "polish-review").apply { mkdirs() }
        val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try { File(out, "${(context.resources.configuration.fontScale*100).toInt()}-${if(dark) "dark" else "light"}-$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { bmp.recycle() }
    }
    private fun content(body: @Composable () -> Unit) = compose.setContent {
        CompositionLocalProvider(LocalContainer provides container) { CraminTheme(darkTheme=dark) { Surface(Modifier.fillMaxSize()) { body() } } }
    }
    private suspend fun seed(): Long {
        val id = container.db.documentDao().insert(DocumentEntity(title="Чтение в контексте: оригинал и перевод",emoji="📚",sourceType=SourceType.TEXT,sourceRef="",sourceLang="en",targetLang="ru",status=DocStatus.READY,progress=1f,errorCode=null,errorMessage=null,direction=null,pipelineVersion=1,briefJson=null,modelsSnapshotJson="""{"roles":{"translate":{"model":"fixture/future-model"}}}""",promptTokens=100,completionTokens=200,costUsd=0.24,audioSeconds=0,wordCount=30,createdAt=1,updatedAt=1))
        container.db.jobDao().insert(JobEntity(documentId=id,kind=JobKind.TRANSLATE,idx=0,rangeStart=0,rangeEnd=0,status=JobStatus.DONE,attempts=1,model="fixture/actual-model-with-a-long-version-identifier",responseJson=null,finishReason=null,promptTokens=100,completionTokens=200,costUsd=0.24,updatedAt=1))
        container.db.sentenceDao().insertAll((0..30).map { SentenceEntity(documentId=id,idx=it,paragraphIdx=it,text="Paragraph $it. Reading preserves context and the position in this material.",segmentId=null) })
        container.db.cardDao().insertCard(CardEntity(documentId=id,lemmaKey="word",meaningKey="one",lemma="word",lemmaVocalized=null,pos=Pos.NOUN,lang="en",targetLang="ru",status=CardStatus.KNOWN,starred=true,firstSentenceIdx=0,updatedAt=1,category=TopicCategory.CORE))
        return id
    }
    @Test fun menuCostsAndTabReturn() {
        val id = runBlocking { seed() }
        content { DocumentScreen(id,"cards",{}, {_,_->},{}) }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("studyButton").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(str(R.string.cards_empty_filter)).assertIsDisplayed()
        compose.onNodeWithTag("docMenu").performClick()
        compose.onNodeWithText(str(R.string.action_delete)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.doc_cost_group)).assertDoesNotExist()
        shot("menu")
        compose.onNodeWithTag("diagnosticsMenuItem").performClick()
        compose.onNodeWithText(str(R.string.doc_cost_group)).performScrollTo()
        compose.waitUntil(5000) { compose.onAllNodesWithText("fixture/actual-model-with-a-long-version-identifier").fetchSemanticsNodes().isNotEmpty() }
        shot("costs")
        compose.onNodeWithText("fixture/actual-model-with-a-long-version-identifier").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("fixture/future-model").performScrollTo().assertIsDisplayed()
        shot("models")
        runBlocking { container.db.documentDao().setUsage(id,100,200,null,2) }
        compose.waitUntil(5000) { compose.onAllNodesWithText(str(R.string.doc_cost_unknown)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(str(R.string.doc_cost_unknown)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(str(R.string.action_back)).performClick()
        compose.onNodeWithTag("tabText").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("readingLoading").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("textList").performScrollToIndex(12)
        compose.onNodeWithText("Paragraph 11. Reading preserves context and the position in this material.").assertIsDisplayed()
        compose.onNodeWithTag("tabCards").performClick()
        compose.onNodeWithTag("tabText").performClick()
        compose.onNodeWithText("Paragraph 11. Reading preserves context and the position in this material.").assertIsDisplayed()
        compose.onNodeWithText(str(R.string.doc_text_empty)).assertDoesNotExist()
    }
    @Test fun delayedReadingEmptyFailureAndDocumentSwitch() {
        val first = MutableSharedFlow<List<SentenceEntity>>(replay=1)
        val next = MutableSharedFlow<List<SentenceEntity>>(replay=1)
        val translations = MutableSharedFlow<List<SegmentEntity>>(replay=1)
        var doc by mutableIntStateOf(1)
        var fail by mutableStateOf(false)
        content {
            key(doc, fail) {
            val stream = remember(doc, fail) { readingStates(if(fail) flow { throw java.io.IOException() } else if(doc==1) first else next,translations,flowOf(emptyList())) }
            val state by stream.collectAsState(ReadingState())
            TextContent(state,TextViewMode.PAIRS,{}, {}, { Text("Материал $doc",Modifier.padding(20.dp)) })
            }
        }
        compose.onNodeWithText(str(R.string.doc_text_loading)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.doc_text_empty)).assertDoesNotExist()
        shot("reading-loading")
        // No timer: the test holds the first database result until after asserting/loading capture.
        compose.runOnIdle { first.tryEmit(listOf(SentenceEntity(1,1,0,0,"The original is available before the translation.",1))) }
        compose.waitUntil(5000) { compose.onAllNodesWithText("The original is available before the translation.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(str(R.string.doc_text_empty)).assertDoesNotExist()
        shot("reading-source")
        compose.runOnIdle { translations.tryEmit(listOf(SegmentEntity(1,1,0,0,"Оригинал доступен ещё до загрузки перевода."))) }
        compose.onNodeWithText("Оригинал доступен ещё до загрузки перевода.").assertIsDisplayed()
        shot("reading-loaded")
        compose.runOnIdle { doc=2 }
        compose.onNodeWithText("The original is available before the translation.").assertDoesNotExist()
        compose.onNodeWithText(str(R.string.doc_text_loading)).assertIsDisplayed()
        compose.runOnIdle { next.tryEmit(emptyList()) }
        compose.onNodeWithText(str(R.string.doc_text_empty)).assertIsDisplayed()
        shot("reading-empty")
        compose.runOnIdle { fail=true }
        compose.onNodeWithText(str(R.string.doc_text_error)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.doc_text_empty)).assertDoesNotExist()
        shot("reading-error")
    }
    @Test fun actualStudyScreenKeepsCompactControls() {
        val id = runBlocking {
            val doc = seed()
            container.db.cardDao().deleteByDocument(doc)
            val segment = container.db.segmentDao().insert(SegmentEntity(documentId=doc,firstSentenceIdx=31,lastSentenceIdx=31,translation="I’ll clearly show how it works, what problems RAG solves, and why it is needed at all."))
            val sentence = container.db.sentenceDao().insertAll(listOf(SentenceEntity(documentId=doc,idx=31,paragraphIdx=31,text="Я наглядно покажу, как всё это работает, какие проблемы RAG решает и зачем вообще надо.",segmentId=segment))).single()
            val card = container.db.cardDao().insertCard(CardEntity(documentId=doc,lemmaKey="работать",meaningKey="work",lemma="работать",lemmaVocalized=null,pos=Pos.VERB,lang="ru",targetLang="en",status=CardStatus.NEW,starred=false,firstSentenceIdx=31,updatedAt=1,category=TopicCategory.CORE))
            val sense = container.db.cardDao().insertSense(SenseEntity(cardId=card,idx=0,translation="work",exampleOccurrenceId=null))
            val occurrence = container.db.cardDao().insertOccurrence(OccurrenceEntity(cardId=card,senseId=sense,sentenceId=sentence,surface="работает",targetSurface="works",start=31,end=39,targetStart=25,targetEnd=30,isExample=true))
            container.db.cardDao().setSenseExample(sense,occurrence)
            doc
        }
        content { pro.perfectproduct.cramin.ui.study.StudyScreen(DeckKey.Document(id,DeckFilter.ALL).key,false,{}) }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("flashCard").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("flashCard").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("cardBack").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("autoplay").assertIsDisplayed()
        compose.onNodeWithTag("undo").assertIsDisplayed()
        shot("study-example")
    }

    @Test fun exampleDividerAndLongScrolling() {
        val short = StudyExample(0,"Я наглядно покажу, как всё это работает, какие проблемы RAG решает и зачем вообще надо.",31,39,"I’ll clearly show how it works, what problems RAG solves, and why it is needed at all.",25,30)
        var ex by mutableStateOf(short)
        var flipped by mutableStateOf(true)
        content {
            FlashCard(StudyCard(1,1,"работать","работать",null,Pos.VERB,Lang.RU,Lang.EN,CardStatus.NEW,false,0,listOf(StudySense(1,"work",ex))),Direction.SRC_FRONT,flipped,setOf(Lang.EN,Lang.RU),{flipped=!flipped},{},{},{_,_->},{},Modifier.padding(20.dp))
        }
        compose.onNodeWithTag("exampleDivider",useUnmergedTree=true).assertExists()
        shot("example-short")
        compose.runOnIdle { ex=short.copy(sentence=short.sentence+" "+"Reading a longer passage keeps the example in context. ".repeat(5),translation=short.translation+" "+"Длинный пример можно прокрутить и прочитать полностью. ".repeat(5)) }
        compose.onNodeWithTag("exampleDivider",useUnmergedTree=true).performScrollTo()
        compose.onNodeWithTag("cardBack",useUnmergedTree=true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, 240f) }
        compose.onNodeWithTag("exampleDivider",useUnmergedTree=true).assertIsDisplayed()
        shot("example-long")
        compose.onNodeWithText(requireNotNull(ex.translation),substring=true,useUnmergedTree=true).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { ex=short.copy(sentence="כך המילה מופיעה בתוך המשפט המקורי.",start=null,end=null,translation="Так слово выглядит в исходном предложении.",targetStart=null,targetEnd=null) }
        shot("example-hebrew")
        compose.runOnIdle { ex=short.copy(translation=null) }
        compose.onNodeWithTag("exampleDivider",useUnmergedTree=true).assertDoesNotExist()
    }
}
