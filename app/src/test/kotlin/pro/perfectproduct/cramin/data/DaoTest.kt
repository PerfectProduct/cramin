package pro.perfectproduct.cramin.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
import pro.perfectproduct.cramin.data.db.StudySessionEntity
import pro.perfectproduct.cramin.data.repo.CardRepository
import pro.perfectproduct.cramin.data.repo.DeckFilter
import pro.perfectproduct.cramin.util.Clock
import pro.perfectproduct.cramin.util.Lang

@RunWith(RobolectricTestRunner::class)
class DaoTest {
    private lateinit var db: CraminDatabase
    private val clock = Clock { 1_000L }

    @Before
    fun setUp() {
        db = CraminDatabase.inMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun doc(title: String, status: DocStatus = DocStatus.READY, createdAt: Long = 1L) = DocumentEntity(
        title = title, emoji = "📄", sourceType = SourceType.TEXT, sourceRef = "", sourceLang = "en", targetLang = "ru",
        status = status, progress = 1f, errorCode = null, errorMessage = null, direction = null, pipelineVersion = 1,
        briefJson = null, modelsSnapshotJson = null, promptTokens = 10, completionTokens = 20, costUsd = 0.01,
        audioSeconds = 0, wordCount = 100, createdAt = createdAt, updatedAt = createdAt,
    )

    private fun card(docId: Long, key: String, status: CardStatus, idx: Int = 0, starred: Boolean = false) = CardEntity(
        documentId = docId, lemmaKey = "$key|NOUN", lemma = key, lemmaVocalized = null, pos = Pos.NOUN, lang = "en",
        targetLang = "ru", status = status, starred = starred, firstSentenceIdx = idx, updatedAt = 0L,
    )

    @Test
    fun libraryCountsAggregateCards() = runTest {
        val d1 = db.documentDao().insert(doc("one"))
        db.cardDao().insertCard(card(d1, "apple", CardStatus.KNOWN))
        db.cardDao().insertCard(card(d1, "pear", CardStatus.NEW))
        db.cardDao().insertCard(card(d1, "plum", CardStatus.LEARNING))
        val lib = db.documentDao().observeLibrary().first()
        assertEquals(1, lib.size)
        assertEquals(3, lib[0].cardCount)
        assertEquals(1, lib[0].knownCount)
        val counts = db.cardDao().observeCounts(d1).first()
        assertEquals(3, counts.total)
        assertEquals(1, counts.newCount)
        assertEquals(1, counts.learning)
        assertEquals(1, counts.known)
    }

    @Test
    fun deletingDocumentCascades() = runTest {
        val d1 = db.documentDao().insert(doc("one"))
        val sIds = db.sentenceDao().insertAll(listOf(SentenceEntity(documentId = d1, idx = 0, paragraphIdx = 0, text = "Hello.", segmentId = null)))
        val cId = db.cardDao().insertCard(card(d1, "hello", CardStatus.NEW))
        val senseId = db.cardDao().insertSense(SenseEntity(cardId = cId, idx = 0, translation = "привет", exampleOccurrenceId = null))
        db.cardDao().insertOccurrence(
            OccurrenceEntity(cardId = cId, senseId = senseId, sentenceId = sIds[0], surface = "Hello", targetSurface = null, start = 0, end = 5, targetStart = null, targetEnd = null, isExample = true),
        )
        db.studySessionDao().upsert(StudySessionEntity("doc:$d1:unlearned", "{}", 0L))
        db.documentDao().delete(d1)
        db.studySessionDao().deleteByPrefix("doc:$d1:")
        assertNull(db.documentDao().getById(d1))
        assertTrue(db.sentenceDao().getByDocument(d1).isEmpty())
        assertTrue(db.cardDao().getByDocument(d1).isEmpty())
        assertTrue(db.cardDao().getSenses(cId).isEmpty())
        assertEquals(0, db.cardDao().countOccurrences(d1))
        assertNull(db.studySessionDao().get("doc:$d1:unlearned"))
    }

    @Test
    fun sharedDeckDeduplicatesByLemmaKeyPreferringNewestDocument() = runTest {
        val repo = CardRepository(db, clock)
        val old = db.documentDao().insert(doc("old", createdAt = 1L))
        val new = db.documentDao().insert(doc("new", createdAt = 2L))
        val processing = db.documentDao().insert(doc("processing", status = DocStatus.TRANSLATING, createdAt = 3L))
        val oldApple = db.cardDao().insertCard(card(old, "apple", CardStatus.LEARNING))
        db.cardDao().insertSense(SenseEntity(cardId = oldApple, idx = 0, translation = "яблоко", exampleOccurrenceId = null))
        db.cardDao().insertSense(SenseEntity(cardId = oldApple, idx = 1, translation = "яблоня", exampleOccurrenceId = null))
        val newApple = db.cardDao().insertCard(card(new, "apple", CardStatus.NEW))
        db.cardDao().insertSense(SenseEntity(cardId = newApple, idx = 0, translation = "Яблоко", exampleOccurrenceId = null))
        db.cardDao().insertCard(card(new, "known", CardStatus.KNOWN))
        db.cardDao().insertCard(card(processing, "pear", CardStatus.NEW))

        val deck = repo.sharedDeckCards(Lang.EN, Lang.RU)
        assertEquals(1, deck.size)
        val apple = deck[0]
        assertEquals(newApple, apple.id)
        assertEquals(listOf(oldApple), apple.duplicateIds)
        assertEquals(listOf("Яблоко", "яблоня"), apple.senses.map { it.translation })

        repo.setStatusForLemma(Lang.EN, Lang.RU, apple.lemmaKey, CardStatus.KNOWN)
        assertEquals(CardStatus.KNOWN, db.cardDao().getStatus(oldApple))
        assertEquals(CardStatus.KNOWN, db.cardDao().getStatus(newApple))
        assertTrue(repo.sharedDeckCards(Lang.EN, Lang.RU).isEmpty())
        assertEquals(0, repo.observeUnlearnedCountForPair(Lang.EN, Lang.RU).first())
    }

    @Test
    fun deckFiltersAndExamples() = runTest {
        val repo = CardRepository(db, clock)
        val d = db.documentDao().insert(doc("d"))
        val sIds = db.sentenceDao().insertAll(
            listOf(
                SentenceEntity(documentId = d, idx = 0, paragraphIdx = 0, text = "The bank was closed.", segmentId = null),
                SentenceEntity(documentId = d, idx = 1, paragraphIdx = 0, text = "We sat on the river bank.", segmentId = null),
            ),
        )
        val seg = db.segmentDao().insert(SegmentEntity(documentId = d, firstSentenceIdx = 0, lastSentenceIdx = 1, translation = "Банк был закрыт. Мы сидели на берегу реки."))
        db.sentenceDao().assignSegment(d, 0, 1, seg)
        val bank = db.cardDao().insertCard(card(d, "bank", CardStatus.NEW, idx = 0, starred = true))
        val s1 = db.cardDao().insertSense(SenseEntity(cardId = bank, idx = 0, translation = "банк", exampleOccurrenceId = null))
        val o1 = db.cardDao().insertOccurrence(
            OccurrenceEntity(cardId = bank, senseId = s1, sentenceId = sIds[0], surface = "bank", targetSurface = "Банк", start = 4, end = 8, targetStart = 0, targetEnd = 4, isExample = true),
        )
        db.cardDao().setSenseExample(s1, o1)
        db.cardDao().insertCard(card(d, "close", CardStatus.KNOWN, idx = 0))

        val unlearned = repo.deckCards(d, DeckFilter.UNLEARNED)
        assertEquals(listOf("bank"), unlearned.map { it.lemma })
        assertEquals(2, repo.deckCards(d, DeckFilter.ALL).size)
        assertEquals(listOf("bank"), repo.deckCards(d, DeckFilter.STARRED).map { it.lemma })

        val ex = unlearned[0].senses[0].example
        assertNotNull(ex)
        assertEquals("The bank was closed.", ex?.sentence)
        assertEquals(4, ex?.start)
        assertEquals("Банк был закрыт. Мы сидели на берегу реки.", ex?.translation)
        assertEquals(0, ex?.targetStart)
    }
}
