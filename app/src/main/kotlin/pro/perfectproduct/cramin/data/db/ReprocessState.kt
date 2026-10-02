package pro.perfectproduct.cramin.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/** Durable snapshot and completion marker, including consumption of legacy files. */
@Entity(tableName = "ReprocessState", foreignKeys = [
    ForeignKey(entity = DocumentEntity::class, parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE),
])
data class ReprocessState(
    @PrimaryKey val documentId: Long,
    val snapshotJson: String,
    val pending: Boolean,
)

@Dao
interface ReprocessDao {
    @Query("SELECT * FROM ReprocessState WHERE documentId = :id")
    suspend fun get(id: Long): ReprocessState?

    @Upsert
    suspend fun put(state: ReprocessState)

    @Query("SELECT r.documentId FROM ReprocessState r JOIN Document d ON d.id = r.documentId WHERE r.pending = 1 AND d.status NOT IN ('READY', 'FAILED')")
    suspend fun queued(): List<Long>
}
