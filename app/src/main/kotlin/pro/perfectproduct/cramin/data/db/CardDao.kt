package pro.perfectproduct.cramin.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Сводка вкладки «Карточки» (SPEC §9.5). */
data class CardCounts(
    val total: Int,
    val newCount: Int,
    val learning: Int,
    val known: Int,
    val starred: Int,
)

/** Вхождение вместе с предложением и сегментом перевода — для примеров и подчёркиваний. */
data class OccurrenceRow(
    @Embedded val occurrence: OccurrenceEntity,
    val sentenceIdx: Int,
    val sentenceText: String,
    val segmentId: Long?,
    val segmentTranslation: String?,
)

/** Снимок статуса карточки для «Обработать заново» (сопоставление по lemmaKey, SPEC §6.11). */
@kotlinx.serialization.Serializable
data class CardStatusSnapshot(
    val lemmaKey: String,
    val status: CardStatus,
    val starred: Boolean,
    val dueAt: Long? = null,
    val intervalDays: Int? = null,
    val ease: Double? = null,
    val reps: Int? = null,
    val lapses: Int? = null,
    val meaningKey: String? = null,
)

data class LangPair(val lang: String, val targetLang: String)

@Dao
interface CardDao {
    // --- Запись -------------------------------------------------------------

    @Insert
    suspend fun insertCard(card: CardEntity): Long

    @Insert
    suspend fun insertSense(sense: SenseEntity): Long

    @Insert
    suspend fun insertOccurrences(occurrences: List<OccurrenceEntity>): List<Long>

    @Insert
    suspend fun insertOccurrence(occurrence: OccurrenceEntity): Long

    @Query("UPDATE Sense SET exampleOccurrenceId = :occurrenceId WHERE id = :senseId")
    suspend fun setSenseExample(senseId: Long, occurrenceId: Long?)

    @Query("UPDATE Card SET status = :status, updatedAt = :now WHERE id = :cardId")
    suspend fun setStatus(cardId: Long, status: CardStatus, now: Long)

    @Query(
        """
        UPDATE Card SET status = :status, updatedAt = :now
        WHERE lang = :lang AND targetLang = :targetLang AND lemmaKey = :lemmaKey AND meaningKey = :meaningKey
        """,
    )
    suspend fun setStatusForMeaning(lang: String, targetLang: String, lemmaKey: String, meaningKey: String, status: CardStatus, now: Long)

    @Query("UPDATE Card SET starred = :starred, updatedAt = :now WHERE id = :cardId")
    suspend fun setStarred(cardId: Long, starred: Boolean, now: Long)

    @Query(
        """
        UPDATE Card SET status = :status, starred = :starred, dueAt = :dueAt, intervalDays = :intervalDays,
            ease = :ease, reps = :reps, lapses = :lapses, updatedAt = :now
        WHERE documentId = :documentId AND lemmaKey = :lemmaKey AND meaningKey = :meaningKey
        """,
    )
    suspend fun restoreSnapshot(
        documentId: Long,
        lemmaKey: String,
        status: CardStatus,
        starred: Boolean,
        dueAt: Long?,
        intervalDays: Int?,
        ease: Double?,
        reps: Int?,
        lapses: Int?,
        now: Long,
        meaningKey: String = "",
    )

    @Query("DELETE FROM Card WHERE documentId = :documentId")
    suspend fun deleteByDocument(documentId: Long)

    // --- Чтение -------------------------------------------------------------

    @Query("SELECT * FROM Card WHERE lang = :lang AND targetLang = :targetLang AND lemmaKey = :lemmaKey")
    suspend fun getLemmaCards(lang: String, targetLang: String, lemmaKey: String): List<CardEntity>

    @Query("SELECT * FROM Card WHERE lang = :lang AND targetLang = :targetLang AND lemmaKey = :lemmaKey AND meaningKey = :meaningKey")
    suspend fun getMeaningCards(lang: String, targetLang: String, lemmaKey: String, meaningKey: String): List<CardEntity>

    @Query("SELECT * FROM Card WHERE id = :cardId")
    suspend fun getCard(cardId: Long): CardEntity?

    @Query("SELECT * FROM Card WHERE id IN (:ids)")
    suspend fun getCards(ids: List<Long>): List<CardEntity>

    @Query("SELECT * FROM Card WHERE documentId = :documentId ORDER BY firstSentenceIdx, id")
    suspend fun getByDocument(documentId: Long): List<CardEntity>

    @Query("SELECT * FROM Card WHERE documentId = :documentId ORDER BY firstSentenceIdx, id")
    fun observeByDocument(documentId: Long): Flow<List<CardEntity>>

    @Query("SELECT status FROM Card WHERE id = :cardId")
    suspend fun getStatus(cardId: Long): CardStatus?

    @Query(
        """
        SELECT COUNT(*) AS total,
            SUM(CASE WHEN status = 'NEW' THEN 1 ELSE 0 END) AS newCount,
            SUM(CASE WHEN status = 'LEARNING' THEN 1 ELSE 0 END) AS learning,
            SUM(CASE WHEN status = 'KNOWN' THEN 1 ELSE 0 END) AS known,
            SUM(CASE WHEN starred THEN 1 ELSE 0 END) AS starred
        FROM Card WHERE documentId = :documentId
        """,
    )
    fun observeCounts(documentId: Long): Flow<CardCounts>

    @Query("SELECT lemmaKey, status, starred, dueAt, intervalDays, ease, reps, lapses, meaningKey FROM Card WHERE documentId = :documentId")
    suspend fun snapshotStatuses(documentId: Long): List<CardStatusSnapshot>

    @Query("SELECT * FROM Sense WHERE cardId = :cardId ORDER BY idx")
    suspend fun getSenses(cardId: Long): List<SenseEntity>

    @Query("SELECT * FROM Sense WHERE cardId IN (:cardIds) ORDER BY cardId, idx")
    suspend fun getSensesForCards(cardIds: List<Long>): List<SenseEntity>

    @Query(
        """
        SELECT o.*, s.idx AS sentenceIdx, s.text AS sentenceText, s.segmentId AS segmentId, seg.translation AS segmentTranslation
        FROM Occurrence o
        JOIN Sentence s ON s.id = o.sentenceId
        LEFT JOIN Segment seg ON seg.id = s.segmentId
        WHERE o.cardId IN (:cardIds)
        ORDER BY o.cardId, s.idx, o.start
        """,
    )
    suspend fun getOccurrencesForCards(cardIds: List<Long>): List<OccurrenceRow>

    @Query(
        """
        SELECT o.*, s.idx AS sentenceIdx, s.text AS sentenceText, s.segmentId AS segmentId, seg.translation AS segmentTranslation
        FROM Occurrence o
        JOIN Sentence s ON s.id = o.sentenceId
        LEFT JOIN Segment seg ON seg.id = s.segmentId
        WHERE s.documentId = :documentId AND o.start IS NOT NULL
        ORDER BY s.idx, o.start
        """,
    )
    fun observeOccurrencesByDocument(documentId: Long): Flow<List<OccurrenceRow>>

    @Query("SELECT COUNT(*) FROM Occurrence o JOIN Card c ON c.id = o.cardId WHERE c.documentId = :documentId")
    suspend fun countOccurrences(documentId: Long): Int

    // --- Общая колода (SPEC §10.6) --------------------------------------------

    @Query(
        """
        SELECT c.* FROM Card c JOIN Document d ON d.id = c.documentId
        WHERE d.status = 'READY' AND c.lang = :lang AND c.targetLang = :targetLang
        ORDER BY d.createdAt DESC, d.id DESC, c.firstSentenceIdx, c.id
        """,
    )
    suspend fun getCardsForPair(lang: String, targetLang: String): List<CardEntity>

    @Query(
        """
        SELECT DISTINCT c.lang AS lang, c.targetLang AS targetLang FROM Card c JOIN Document d ON d.id = c.documentId
        WHERE d.status = 'READY' ORDER BY c.lang, c.targetLang
        """,
    )
    fun observeLangPairs(): Flow<List<LangPair>>

    @Query(
        """
        SELECT COUNT(DISTINCT c.lemmaKey || char(0) || c.meaningKey) FROM Card c JOIN Document d ON d.id = c.documentId
        WHERE d.status = 'READY' AND c.status != 'KNOWN' AND c.lang = :lang AND c.targetLang = :targetLang
        """,
    )
    fun observeUnlearnedCountForPair(lang: String, targetLang: String): Flow<Int>

    @Transaction
    suspend fun insertCardWithSenses(card: CardEntity, senses: List<String>): Pair<Long, List<Long>> {
        val cardId = insertCard(card)
        val senseIds = senses.mapIndexed { i, t -> insertSense(SenseEntity(cardId = cardId, idx = i, translation = t, exampleOccurrenceId = null)) }
        return cardId to senseIds
    }
}
