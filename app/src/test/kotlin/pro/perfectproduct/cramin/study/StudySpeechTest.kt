package pro.perfectproduct.cramin.study

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import pro.perfectproduct.cramin.app.AppContainer
import pro.perfectproduct.cramin.data.db.*
import pro.perfectproduct.cramin.data.repo.*
import pro.perfectproduct.cramin.ui.study.StudyViewModel
import pro.perfectproduct.cramin.util.Lang
import java.nio.file.Files
import java.util.Locale

/** Recording system TTS fake: verifies calls, not audibility or installed voice quality. */
@Implements(TextToSpeech::class)
class RecordingStudyTts {
    private var language: Locale = Locale.ROOT
    @Implementation fun __constructor__(context: Context, listener: TextToSpeech.OnInitListener) {
        Handler(Looper.getMainLooper()).post { listener.onInit(TextToSpeech.SUCCESS) }
    }
    @Implementation fun setOnUtteranceProgressListener(listener: UtteranceProgressListener): Int = 0
    @Implementation fun isLanguageAvailable(locale: Locale): Int = TextToSpeech.LANG_AVAILABLE
    @Implementation fun setLanguage(locale: Locale): Int { language=locale;return 0 }
    @Implementation fun setSpeechRate(rate: Float): Int = 0
    @Implementation fun speak(text: CharSequence, queueMode: Int, params: Bundle?, utteranceId: String): Int {
        calls += text.toString() to language.language
        return 0
    }
    @Implementation fun stop(): Int = 0
    @Implementation fun shutdown() = Unit
    companion object { val calls = mutableListOf<Pair<String,String>>() }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[34],shadows=[RecordingStudyTts::class])
class StudySpeechTest {
    private val scheduler=TestCoroutineScheduler()
    private val store=ViewModelStore()
    private lateinit var container: AppContainer
    private lateinit var vm: StudyViewModel
    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        val context=ApplicationProvider.getApplicationContext<Context>()
        val dir=Files.createTempDirectory("study-speech").toFile()
        container=AppContainer(context,databaseProvider={CraminDatabase.inMemory(context)},
            settingsDataStoreProvider={ scope -> PreferenceDataStoreFactory.create(scope=scope) { dir.resolve("settings.preferences_pb") } })
        RecordingStudyTts.calls.clear()
        container.tts
        shadowOf(Looper.getMainLooper()).idle()
    }
    @After fun cleanup() {
        store.clear();scheduler.runCurrent();container.tts.shutdown();container.appScope.cancel();container.db.close()
        Dispatchers.resetMain()
    }
    private fun await(predicate: () -> Boolean) {
        val deadline=System.nanoTime()+10_000_000_000L
        while(!predicate() && System.nanoTime()<deadline) { scheduler.runCurrent();Thread.sleep(5) }
        scheduler.runCurrent();assertTrue("Timed out waiting for Room/ViewModel",predicate())
    }
    private fun start(direction: Direction) {
        val doc=runBlocking {
            container.settingsStore.setAutoSpeak(true)
            container.settingsStore.setAutoplayIntervals(500,500)
            val db=container.db
            val id=db.documentDao().insert(DocumentEntity(title="Speech fixture",emoji="📚",sourceType=SourceType.TEXT,sourceRef="",sourceLang="he",targetLang="ru",
                status=DocStatus.READY,progress=1f,errorCode=null,errorMessage=null,direction=direction,pipelineVersion=1,briefJson=null,modelsSnapshotJson=null,
                promptTokens=0,completionTokens=0,costUsd=null,audioSeconds=0,wordCount=5,createdAt=1,updatedAt=1))
            repeat(3) { i ->
                val card=db.cardDao().insertCard(CardEntity(documentId=id,lemmaKey="ספר|$i",meaningKey="книга|$i",lemma="ספר",lemmaVocalized="סֵפֶר",pos=Pos.NOUN,
                    lang="he",targetLang="ru",status=CardStatus.NEW,starred=false,firstSentenceIdx=i,updatedAt=1))
                val sense=db.cardDao().insertSense(SenseEntity(cardId=card,idx=0,translation="книга",exampleOccurrenceId=null))
                val seg=db.segmentDao().insert(SegmentEntity(documentId=id,firstSentenceIdx=i,lastSentenceIdx=i,translation="Книга лежит на столе."))
                val sentence=db.sentenceDao().insertAll(listOf(SentenceEntity(documentId=id,idx=i,paragraphIdx=0,text="סֵפֶר עַל הַשֻּׁלְחָן.",segmentId=seg))).single()
                val occ=db.cardDao().insertOccurrence(OccurrenceEntity(cardId=card,senseId=sense,sentenceId=sentence,surface="ספר",targetSurface="Книга",start=0,end=5,targetStart=0,targetEnd=5,isExample=true))
                db.cardDao().setSenseExample(sense,occ)
            }
            id
        }
        vm=StudyViewModel(container,DeckKey.Document(doc,DeckFilter.ALL).key,false)
        store.put("study",vm)
        await { vm.state.value.session!=null && vm.state.value.ttsLangs.size==Lang.entries.size }
    }
    @Test fun automaticSpeechOnFlipUsesVocalizedWordAndCorrectLanguageInBothDirections() {
        start(Direction.SRC_FRONT)
        vm.speakCurrent();assertEquals("סֵפֶר" to "he",RecordingStudyTts.calls.last())
        vm.dispatch(SessionEvent.Flip);await { vm.state.value.session?.isFlipped==true && RecordingStudyTts.calls.size==2 }
        assertEquals("книга" to "ru",RecordingStudyTts.calls.last())
        vm.setDirection(Direction.TGT_FRONT);await { vm.state.value.direction==Direction.TGT_FRONT }
        vm.dispatch(SessionEvent.Flip);await { vm.state.value.session?.isFlipped==false && RecordingStudyTts.calls.size==3 }
        assertEquals("книга" to "ru",RecordingStudyTts.calls.last())
        vm.dispatch(SessionEvent.Flip);await { vm.state.value.session?.isFlipped==true && RecordingStudyTts.calls.size==4 }
        assertEquals("סֵפֶר" to "he",RecordingStudyTts.calls.last())
        assertEquals(listOf("סֵפֶר" to "he","книга" to "ru","книга" to "ru","סֵפֶר" to "he"),RecordingStudyTts.calls)
    }
    @Test fun autoplaySpeechTicksPreserveStatusesAndTouchCancelsTimer() {
        start(Direction.TGT_FRONT)
        val ids=vm.state.value.cards.keys.toList()
        vm.dispatch(SessionEvent.ToggleAutoplay);await { vm.state.value.session?.autoplay==true }
        assertEquals("книга" to "ru",RecordingStudyTts.calls.last())
        scheduler.advanceTimeBy(500);scheduler.runCurrent();await { vm.state.value.session?.isFlipped==true && RecordingStudyTts.calls.size>=2 }
        assertEquals("סֵפֶר" to "he",RecordingStudyTts.calls.last())
        scheduler.advanceTimeBy(500);scheduler.runCurrent();await { vm.state.value.session?.position==1 && RecordingStudyTts.calls.size>=3 }
        assertEquals("книга" to "ru",RecordingStudyTts.calls.last())
        vm.interactionStart();await { vm.state.value.session?.autoplay==false }
        val count=RecordingStudyTts.calls.size
        scheduler.advanceTimeBy(5000);scheduler.runCurrent()
        assertEquals(count,RecordingStudyTts.calls.size);assertEquals(1,vm.state.value.session?.position)
        assertEquals(List(3) { CardStatus.NEW },runBlocking { ids.map { container.cardRepository.getStatus(it) } })
    }
}
