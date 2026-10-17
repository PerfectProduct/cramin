package pro.perfectproduct.cramin.ui

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import pro.perfectproduct.cramin.app.theme.CraminTheme
import pro.perfectproduct.cramin.data.db.*
import pro.perfectproduct.cramin.data.repo.*
import pro.perfectproduct.cramin.testing.TestContainer
import pro.perfectproduct.cramin.ui.study.*
import pro.perfectproduct.cramin.util.Lang
import java.io.File

/** Offline, isolated Room fixtures. Screenshots capture the actual complete StudyScreen. */
class CardContextTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var container: TestContainer
    private val store = ViewModelStore()
    private lateinit var vm: StudyViewModel
    private val src = "They sat on the bank of the river."
    private val tgt = "Они сидели на берегу реки."

    @Before fun setup() { container = TestContainer(context) }
    @After fun cleanup() {
        compose.runOnIdle { store.clear(); container.tts.shutdown() }
        container.db.close()
    }
    private fun open(direction: Direction = Direction.SRC_FRONT, source: String = src, target: String? = tgt,
                     form: String = "bank", targetForm: String? = "берегу", lemma: String = "bank", meaning: String = "берег",
                     language: Lang = Lang.EN, example: Boolean = true, binding: Boolean = true, pos: Pos = Pos.NOUN) {
        val id = runBlocking {
            val db = container.db
            val doc = db.documentDao().insert(DocumentEntity(title="Context fixture",emoji="📚",sourceType=SourceType.TEXT,
                sourceRef="",sourceLang=language.code,targetLang="ru",status=DocStatus.READY,progress=1f,errorCode=null,errorMessage=null,
                direction=direction,pipelineVersion=1,briefJson=null,modelsSnapshotJson=null,promptTokens=0,completionTokens=0,
                costUsd=null,audioSeconds=0,wordCount=10,createdAt=1,updatedAt=1))
            val seg = target?.let { db.segmentDao().insert(SegmentEntity(documentId=doc,firstSentenceIdx=0,lastSentenceIdx=2,translation=it)) }
            val sentence = db.sentenceDao().insertAll(listOf(SentenceEntity(documentId=doc,idx=0,paragraphIdx=0,text=source,segmentId=seg))).single()
            repeat(3) { i ->
                val card = db.cardDao().insertCard(CardEntity(documentId=doc,lemmaKey="$lemma|NOUN|$i",meaningKey="$meaning|$i",lemma=lemma,
                    lemmaVocalized=if(language==Lang.HE) "סֵפֶר" else null,pos=pos,lang=language.code,targetLang="ru",status=CardStatus.NEW,
                    starred=false,firstSentenceIdx=i,updatedAt=1))
                val sense=db.cardDao().insertSense(SenseEntity(cardId=card,idx=0,translation=meaning,exampleOccurrenceId=null))
                if(example) {
                    val a=source.indexOf(form); val b=targetForm?.let { target?.indexOf(it) }
                    val occurrence=db.cardDao().insertOccurrence(OccurrenceEntity(cardId=card,senseId=sense,sentenceId=sentence,
                        surface=form,targetSurface=targetForm,start=if(binding) a else null,end=if(binding) a+form.length else null,
                        targetStart=if(binding) b else null,targetEnd=if(binding && b!=null) b+requireNotNull(targetForm).length else null,isExample=true))
                    db.cardDao().setSenseExample(sense,occurrence)
                }
            }
            doc
        }
        compose.setContent {
            CraminTheme(darkTheme = InstrumentationRegistry.getArguments().getString("dark") != "false") {
                val viewModel = remember { StudyViewModel(container, DeckKey.Document(id,DeckFilter.ALL).key,false).also { vm=it;store.put("study",it) } }
                StudyScreen(DeckKey.Document(id,DeckFilter.ALL).key,false,{},vm=viewModel)
            }
        }
        compose.waitUntil(15000) { compose.onAllNodesWithTag("cardFront").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun flip() {
        compose.onNodeWithTag("flashCard").performTouchInput { click() }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("cardBack").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
    private fun expectContext(text: String, highlighted: String?) {
        val node=compose.onNodeWithTag("cardContext",useUnmergedTree=true).assertTextEquals(text).fetchSemanticsNode()
        val annotated=node.config[SemanticsProperties.Text].single()
        val spans=annotated.spanStyles
        assertEquals(if(highlighted==null) emptyList<String>() else listOf(highlighted),spans.map { annotated.text.substring(it.start,it.end) })
    }
    private fun shot(name: String) {
        compose.waitForIdle()
        // Semantics may be committed before the frame reaches SurfaceFlinger (especially after scrolling).
        val drawn = java.util.concurrent.CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            android.view.Choreographer.getInstance().postFrameCallback {
                android.view.Choreographer.getInstance().postFrameCallback { drawn.countDown() }
            }
        }
        assertTrue("Two display frames must complete before capture", drawn.await(5, java.util.concurrent.TimeUnit.SECONDS))
        compose.onNodeWithTag("autoplay").assertIsDisplayed()
        compose.onNodeWithTag("undo").assertIsDisplayed()
        val bitmap=requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val dir=File(output ?: requireNotNull(context.getExternalFilesDir(null)).absolutePath,"symmetric-cards").apply { mkdirs() }
        try { File(dir,"$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) } }
        finally { bitmap.recycle() }
    }
    private fun endShot(face: String, name: String) {
        val before = vm.state.value.session
        compose.onNodeWithTag(face).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        compose.waitForIdle()
        val range = compose.onNodeWithTag(face).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals(range.maxValue(), range.value(), 2f)
        assertEquals(before?.position, vm.state.value.session?.position)
        assertEquals(before?.isFlipped, vm.state.value.session?.isFlipped)
        shot(name)
    }
    @Test fun sourceFirstQuestionAndAnswerHideOppositeSemantics() {
        open()
        expectContext(src,"bank")
        compose.onNodeWithText(tgt,useUnmergedTree=true).assertDoesNotExist()
        compose.onNodeWithText("берег",useUnmergedTree=true).assertDoesNotExist()
        shot("src-front-question")
        endShot("cardFront", "src-front-question-scrolled")
        flip()
        expectContext(tgt,"берегу")
        compose.onNodeWithText(src,useUnmergedTree=true).assertDoesNotExist()
        compose.onNodeWithText("bank",useUnmergedTree=true).assertDoesNotExist()
        shot("src-front-answer")
        endShot("cardBack", "src-front-answer-scrolled")
        compose.onNodeWithTag("star").performClick()
        compose.waitUntil(5000) { vm.state.value.currentCard?.starred==true }
        compose.onNodeWithTag("flashCard").assertHasClickAction()
        val actions=compose.onNodeWithTag("flashCard").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.CustomActions]
        assertEquals(2,actions.size)
    }
    @Test fun targetFirstQuestionAndAnswerHideOppositeSemantics() {
        open(Direction.TGT_FRONT)
        expectContext(tgt,"берегу")
        compose.onNodeWithText(src,useUnmergedTree=true).assertDoesNotExist()
        shot("tgt-front-question")
        endShot("cardFront", "tgt-front-question-scrolled")
        flip()
        expectContext(src,"bank")
        compose.onNodeWithText(tgt,useUnmergedTree=true).assertDoesNotExist()
        shot("tgt-front-answer")
        endShot("cardBack", "tgt-front-answer-scrolled")
    }
    @Test fun approvedBookPairChangesWholeSidesWithSessionDirection() {
        open(source="I read a book.", target="Я читаю книгу.", form="book", targetForm="книгу", lemma="book", meaning="книга")
        expectContext("I read a book.", "book");shot("book-en-ru-front")
        flip();expectContext("Я читаю книгу.", "книгу");shot("book-en-ru-back")
        compose.onNodeWithContentDescription("Настройки сессии").performClick()
        compose.onNodeWithText("Русский").performClick()
        androidx.test.espresso.Espresso.pressBack()
        compose.waitUntil(5000) { vm.state.value.direction == Direction.TGT_FRONT }
        // Direction changes the complete sides; the current flipped state is retained.
        expectContext("I read a book.", "book");shot("book-ru-en-back")
        compose.onNodeWithTag("flashCard").performTouchInput { click() }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("cardFront").fetchSemanticsNodes().isNotEmpty() }
        expectContext("Я читаю книгу.", "книгу");shot("book-ru-en-front")
    }
    @Test fun wholeExpression() {
        open(source="They gave up after a long day.",target="Они сдались после долгого дня.",form="gave up",targetForm="сдались",lemma="give up",meaning="сдаться",pos=Pos.VERB)
        expectContext("They gave up after a long day.","gave up");shot("expression-question")
        endShot("cardFront", "expression-question-scrolled")
        flip();expectContext("Они сдались после долгого дня.","сдались");shot("expression-answer")
        endShot("cardBack", "expression-answer-scrolled")
    }
    @Test fun longSegmentScrollReachesEndWithoutFlipOrSort() {
        val longTarget=tgt+" Они обсуждали дорогу домой и вспоминали прошедший день.".repeat(22)+" КОНЕЦ СЕГМЕНТА."
        open(Direction.TGT_FRONT,target=longTarget)
        expectContext(longTarget,"берегу");shot("long-segment-top")
        val before=vm.state.value.session
        repeat(28) { compose.onNodeWithTag("cardFront").performTouchInput { swipeUp() } }
        val range=compose.onNodeWithTag("cardFront").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals(range.maxValue(),range.value(),2f)
        assertEquals(before?.position,vm.state.value.session?.position)
        assertEquals(before?.isFlipped,vm.state.value.session?.isFlipped)
        shot("long-segment-end")
        flip();expectContext(src,"bank");shot("long-segment-source-answer")
    }
    @Test fun hebrewWithMarksAndPhysicalSwipesInReverseDirection() {
        open(Direction.TGT_FRONT,source="הַסֵּפֶר הַחָדָשׁ מֻנָּח עַל הַשֻּׁלְחָן.",target="Новая книга лежит на столе.",form="סֵּפֶר",targetForm="книга",lemma="ספר",meaning="книга",language=Lang.HE)
        expectContext("Новая книга лежит на столе.","книга");shot("hebrew-target-question")
        flip();expectContext("הַסֵּפֶר הַחָדָשׁ מֻנָּח עַל הַשֻּׁלְחָן.","סֵּפֶר");shot("hebrew-source-answer")
        endShot("cardBack", "hebrew-source-answer-scrolled")
        val id=requireNotNull(vm.state.value.currentCard).id
        compose.onNodeWithTag("flashCard").performTouchInput { swipeRight() }
        compose.waitUntil(5000) { vm.state.value.session?.position==1 }
        assertEquals(CardStatus.KNOWN,runBlocking { container.cardRepository.getStatus(id) })
        compose.onNodeWithTag("undo").performClick()
        compose.waitUntil(5000) { vm.state.value.session?.position==0 }
        assertEquals(CardStatus.NEW,runBlocking { container.cardRepository.getStatus(id) })
        // Undo restores the flipped card; physical left remains learning under mirrored graphics/RTL content.
        compose.onNodeWithTag("flashCard").performTouchInput { swipeLeft() }
        compose.waitUntil(5000) { vm.state.value.session?.position==1 }
        assertEquals(CardStatus.LEARNING,runBlocking { container.cardRepository.getStatus(id) })
    }
    @Test fun noExampleKeepsActionsOnBothSides() {
        open(example=false)
        compose.onNodeWithTag("cardContext",useUnmergedTree=true).assertDoesNotExist();shot("no-example-question")
        flip();compose.onNodeWithTag("cardContext",useUnmergedTree=true).assertDoesNotExist();shot("no-example-answer")
    }
    @Test fun noTranslationDoesNotLeakSourceOnAnswer() {
        open(target=null)
        expectContext(src,"bank");shot("no-translation-question")
        flip();compose.onNodeWithTag("cardContext",useUnmergedTree=true).assertDoesNotExist()
        compose.onNodeWithText(src,useUnmergedTree=true).assertDoesNotExist();shot("no-translation-answer")
    }
    @Test fun missingBindingsKeepBothTextsUnhighlighted() {
        open(binding=false)
        expectContext(src,null);shot("no-binding-question")
        flip();expectContext(tgt,null);shot("no-binding-answer")
    }
    @Test fun repeatedTargetDoesNotGuessFirstOccurrence() {
        open(target="На берегу реки, на берегу озера.")
        flip();expectContext("На берегу реки, на берегу озера.",null);shot("repeated-target-answer")
    }
    @Test fun manualSpeechUsesOnlyCurrentWordAndLanguage() {
        val spoken=mutableListOf<Pair<String,Lang>>()
        var flipped by mutableStateOf(false)
        var direction by mutableStateOf(Direction.SRC_FRONT)
        val card=StudyCard(1,1,"bank","bank",null,Pos.NOUN,Lang.EN,Lang.RU,CardStatus.NEW,false,0,
            listOf(StudySense(1,"берег",StudyExample(0,src,16,20,tgt,14,20,"bank","берегу"))))
        compose.setContent { CraminTheme { FlashCard(card,direction,flipped,Lang.entries.toSet(),{flipped=!flipped},{},{},{t,l->spoken+=t to l},{},Modifier.fillMaxSize()) } }
        for (d in Direction.entries) {
            compose.runOnIdle { direction=d;flipped=false }
            compose.waitForIdle();compose.onNodeWithTag("speak").performClick()
            compose.onNodeWithTag("flashCard").performTouchInput { click() };compose.waitForIdle()
            compose.onNodeWithTag("speak").performClick()
        }
        assertEquals(listOf("bank" to Lang.EN,"берег" to Lang.RU,"берег" to Lang.RU,"bank" to Lang.EN),spoken)
    }
}
