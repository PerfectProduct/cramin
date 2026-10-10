package pro.perfectproduct.cramin.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SentenceDao {
    @Insert
    suspend fun insertAll(sentences: List<SentenceEntity>): List<Long>

    @Query("SELECT * FROM Sentence WHERE documentId = :documentId ORDER BY idx")
    suspend fun getByDocument(documentId: Long): List<SentenceEntity>

    @Query("SELECT * FROM Sentence WHERE documentId = :documentId ORDER BY idx")
    fun observeByDocument(documentId: Long): Flow<List<SentenceEntity>>

    @Query("SELECT COUNT(*) FROM Sentence WHERE documentId = :documentId")
    suspend fun countByDocument(documentId: Long): Int

    @Query("UPDATE Sentence SET segmentId = :segmentId WHERE documentId = :documentId AND idx BETWEEN :fromIdx AND :toIdx")
    suspend fun assignSegment(documentId: Long, fromIdx: Int, toIdx: Int, segmentId: Long)

    @Query("DELETE FROM Sentence WHERE documentId = :documentId")
    suspend fun deleteByDocument(documentId: Long)
}

@Dao
interface SegmentDao {
    @Insert
    suspend fun insert(segment: SegmentEntity): Long

    @Query("SELECT * FROM Segment WHERE documentId = :documentId ORDER BY firstSentenceIdx")
    suspend fun getByDocument(documentId: Long): List<SegmentEntity>

    @Query("SELECT * FROM Segment WHERE documentId = :documentId ORDER BY firstSentenceIdx")
    fun observeByDocument(documentId: Long): Flow<List<SegmentEntity>>

    @Query("DELETE FROM Segment WHERE documentId = :documentId")
    suspend fun deleteByDocument(documentId: Long)

    @Query("DELETE FROM Segment WHERE documentId = :documentId AND firstSentenceIdx >= :fromIdx")
    suspend fun deleteFrom(documentId: Long, fromIdx: Int)
}

data class JobUsage(val promptTokens: Long, val completionTokens: Long, val costUsd: Double?)

@Dao
interface JobDao {
    @Insert
    suspend fun insert(job: JobEntity): Long

    @Insert
    suspend fun insertAll(jobs: List<JobEntity>): List<Long>

    @Update
    suspend fun update(job: JobEntity)

    @Query("SELECT * FROM Job WHERE id = :id")
    suspend fun getById(id: Long): JobEntity?

    @Query("SELECT * FROM Job WHERE documentId = :documentId ORDER BY kind, idx")
    suspend fun getByDocument(documentId: Long): List<JobEntity>

    @Query("SELECT * FROM Job WHERE documentId = :documentId ORDER BY kind, idx")
    fun observeByDocument(documentId: Long): Flow<List<JobEntity>>

    @Query("SELECT * FROM Job WHERE documentId = :documentId AND kind = :kind ORDER BY idx")
    suspend fun getByKind(documentId: Long, kind: JobKind): List<JobEntity>

    @Query("SELECT * FROM Job WHERE documentId = :documentId AND kind = :kind AND idx = :idx")
    suspend fun get(documentId: Long, kind: JobKind, idx: Int): JobEntity?

    @Query("DELETE FROM Job WHERE documentId = :documentId AND kind = :kind")
    suspend fun deleteByKind(documentId: Long, kind: JobKind)

    @Query("DELETE FROM Job WHERE documentId = :documentId")
    suspend fun deleteByDocument(documentId: Long)

    @Query(
        """
        SELECT COALESCE(SUM(promptTokens), 0) AS promptTokens, COALESCE(SUM(completionTokens), 0) AS completionTokens,
            CASE WHEN COUNT(CASE WHEN promptTokens > 0 OR completionTokens > 0 THEN 1 END) = COUNT(CASE WHEN promptTokens > 0 OR completionTokens > 0 THEN costUsd END) THEN SUM(costUsd) ELSE NULL END AS costUsd FROM Job WHERE documentId = :documentId
        """,
    )
    suspend fun sumUsage(documentId: Long): JobUsage
}

@Dao
interface StudySessionDao {
    @Query("SELECT * FROM StudySession WHERE deckKey = :deckKey")
    suspend fun get(deckKey: String): StudySessionEntity?

    @Query("SELECT * FROM StudySession WHERE deckKey = :deckKey")
    fun observe(deckKey: String): Flow<StudySessionEntity?>

    @androidx.room.Upsert
    suspend fun upsert(session: StudySessionEntity)

    @Query("DELETE FROM StudySession WHERE deckKey = :deckKey")
    suspend fun delete(deckKey: String)

    @Query("DELETE FROM StudySession WHERE deckKey LIKE :prefix || '%'")
    suspend fun deleteByPrefix(prefix: String)
}
