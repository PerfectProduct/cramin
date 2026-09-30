package pro.perfectproduct.cramin.pipeline

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pro.perfectproduct.cramin.data.db.DocStatus
import pro.perfectproduct.cramin.data.repo.DeckFilter
import pro.perfectproduct.cramin.data.repo.NewDocument
import pro.perfectproduct.cramin.testing.Fixtures
import pro.perfectproduct.cramin.testing.TestContainer
import pro.perfectproduct.cramin.util.Lang

/** SPEC §14.2: ProcessDocumentWorker с FakeLlmClient доводит документ до READY (android.icu, res/raw, Room на устройстве). */
@RunWith(AndroidJUnit4::class)
class ProcessDocumentWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun workerBringsDocumentToReady() = runBlocking {
        val container = TestContainer(context)
        val id = container.documentRepository.create(NewDocument.Text(Fixtures.text(Lang.EN), null, Lang.RU, null))
        val worker = TestListenableWorkerBuilder<ProcessDocumentWorker>(context)
            .setInputData(workDataOf(ProcessDocumentWorker.KEY_DOCUMENT_ID to id))
            .setWorkerFactory(container.workerFactory)
            .build()
        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(workDataOf(ProcessDocumentWorker.KEY_CARDS to result.outputData.getInt(ProcessDocumentWorker.KEY_CARDS, -1))), result)
        val doc = container.documentRepository.get(id)!!
        assertEquals(DocStatus.READY, doc.status)
        assertEquals(1f, doc.progress)
        val cards = container.cardRepository.deckCards(id, DeckFilter.ALL)
        assertTrue("cards=${cards.size}", cards.size >= 40)
        val bank = cards.first { it.lemmaKey == "bank|NOUN" }
        assertEquals(2, bank.senses.size)
        assertTrue(container.fakeLlm.requests.size >= 6)
        container.db.close()
    }

    @Test
    fun workerFailsCleanlyOnSameLanguage() = runBlocking {
        val container = TestContainer(context)
        val id = container.documentRepository.create(NewDocument.Text(Fixtures.text(Lang.RU), null, Lang.RU, null))
        val worker = TestListenableWorkerBuilder<ProcessDocumentWorker>(context)
            .setInputData(workDataOf(ProcessDocumentWorker.KEY_DOCUMENT_ID to id))
            .setWorkerFactory(container.workerFactory)
            .build()
        val result = worker.doWork()
        val doc = container.documentRepository.get(id)!!
        assertEquals("SAME_LANGUAGE", doc.errorCode)
        assertEquals(DocStatus.FAILED, doc.status)
        assertEquals("result=$result", ListenableWorker.Result.failure(workDataOf(ProcessDocumentWorker.KEY_ERROR to "SAME_LANGUAGE")), result)
        container.db.close()
    }
}
