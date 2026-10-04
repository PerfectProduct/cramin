package pro.perfectproduct.cramin.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Документ плюс агрегаты для плашки библиотеки (SPEC §9.1, §13). */
data class DocumentWithCounts(
    @Embedded val document: DocumentEntity,
    val cardCount: Int,
    val knownCount: Int,
)

data class UsageTotals(
    val promptTokens: Long,
    val completionTokens: Long,
    val costUsd: Double,
    val documents: Int,
)

@Dao
interface DocumentDao {
    @Query("UPDATE Document SET categoryMask = (categoryMask | :bit) - (categoryMask & :bit) WHERE id = :id")
    suspend fun toggleCategory(id: Long, bit: Int)
    @Query("UPDATE Document SET categoryMask = :mask WHERE id = :id")
    suspend fun setCategoryMask(id: Long, mask: Int)
    @Query("UPDATE Document SET topicSnapshotJson = :snapshot WHERE id = :id")
    suspend fun setTopicSnapshot(id: Long, snapshot: String?)
    @Query("UPDATE Document SET topicError = :error WHERE id = :id")
    suspend fun setTopicError(id: Long, error: String?)

    @Insert
    suspend fun insert(document: DocumentEntity): Long

    @Update
    suspend fun update(document: DocumentEntity)

    @Query("SELECT * FROM Document WHERE id = :id")
    suspend fun getById(id: Long): DocumentEntity?

    @Query("SELECT * FROM Document WHERE id = :id")
    fun observeById(id: Long): Flow<DocumentEntity?>

    @Query("SELECT * FROM Document ORDER BY createdAt DESC")
    suspend fun getAll(): List<DocumentEntity>

    @Query(
        """
        SELECT d.*,
            (SELECT COUNT(*) FROM Card c WHERE c.documentId = d.id) AS cardCount,
            (SELECT COUNT(*) FROM Card c WHERE c.documentId = d.id AND c.status = 'KNOWN') AS knownCount
        FROM Document d
        ORDER BY d.createdAt DESC
        """,
    )
    fun observeLibrary(): Flow<List<DocumentWithCounts>>

    @Query(
        """
        SELECT d.*,
            (SELECT COUNT(*) FROM Card c WHERE c.documentId = d.id) AS cardCount,
            (SELECT COUNT(*) FROM Card c WHERE c.documentId = d.id AND c.status = 'KNOWN') AS knownCount
        FROM Document d WHERE d.id = :id
        """,
    )
    fun observeWithCounts(id: Long): Flow<DocumentWithCounts?>

    @Query("DELETE FROM Document WHERE id = :id")
    suspend fun delete(id: Long)

    @Query(
        """
        UPDATE Document SET status = :status, progress = :progress, errorCode = :errorCode,
            errorMessage = :errorMessage, updatedAt = :now WHERE id = :id
        """,
    )
    suspend fun setStatus(id: Long, status: DocStatus, progress: Float, errorCode: String?, errorMessage: String?, now: Long)

    @Query("UPDATE Document SET studyNotice = :value WHERE id = :id")
    suspend fun setStudyNotice(id: Long, value: Boolean)

    @Query("UPDATE Document SET failureJson = :json WHERE id = :id")
    suspend fun setFailure(id: Long, json: String?)

    @Query("UPDATE Document SET progress = :progress, updatedAt = :now WHERE id = :id")
    suspend fun setProgress(id: Long, progress: Float, now: Long)

    @Query("UPDATE Document SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun setTitle(id: Long, title: String, now: Long)

    @Query("UPDATE Document SET title = :title, emoji = :emoji, briefJson = :briefJson, updatedAt = :now WHERE id = :id")
    suspend fun setBrief(id: Long, title: String, emoji: String, briefJson: String?, now: Long)

    @Query("UPDATE Document SET direction = :direction, updatedAt = :now WHERE id = :id")
    suspend fun setDirection(id: Long, direction: Direction?, now: Long)

    @Query("UPDATE Document SET sourceLang = :sourceLang, updatedAt = :now WHERE id = :id")
    suspend fun setSourceLang(id: Long, sourceLang: String, now: Long)

    @Query("UPDATE Document SET targetLang = :targetLang, updatedAt = :now WHERE id = :id")
    suspend fun setTargetLang(id: Long, targetLang: String, now: Long)

    @Query("UPDATE Document SET wordCount = :wordCount, updatedAt = :now WHERE id = :id")
    suspend fun setWordCount(id: Long, wordCount: Int, now: Long)

    @Query("UPDATE Document SET audioSeconds = :audioSeconds, updatedAt = :now WHERE id = :id")
    suspend fun setAudioSeconds(id: Long, audioSeconds: Int, now: Long)

    @Query("UPDATE Document SET modelsSnapshotJson = :snapshot, pipelineVersion = :pipelineVersion, updatedAt = :now WHERE id = :id")
    suspend fun setPipelineSnapshot(id: Long, snapshot: String?, pipelineVersion: Int, now: Long)

    @Query(
        """
        UPDATE Document SET promptTokens = :promptTokens, completionTokens = :completionTokens,
            costUsd = :costUsd, updatedAt = :now WHERE id = :id
        """,
    )
    suspend fun setUsage(id: Long, promptTokens: Int, completionTokens: Int, costUsd: Double?, now: Long)

    @Query(
        """
        SELECT COALESCE(SUM(promptTokens), 0) AS promptTokens, COALESCE(SUM(completionTokens), 0) AS completionTokens,
            COALESCE(SUM(costUsd), 0.0) AS costUsd, COUNT(*) AS documents FROM Document
        """,
    )
    fun observeUsageTotals(): Flow<UsageTotals>

    @Query("SELECT COUNT(*) FROM Document WHERE status = 'READY'")
    fun observeReadyCount(): Flow<Int>
}
