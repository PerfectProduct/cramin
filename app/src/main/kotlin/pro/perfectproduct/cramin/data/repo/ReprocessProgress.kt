package pro.perfectproduct.cramin.data.repo

import kotlinx.serialization.json.Json
import pro.perfectproduct.cramin.data.db.CardStatusSnapshot
import pro.perfectproduct.cramin.data.db.CraminDatabase
import pro.perfectproduct.cramin.data.db.ReprocessState

/** Called under the document lock and a Room transaction. Files are read only for v1 import. */
class ReprocessProgress(private val db: CraminDatabase, private val files: DocumentFiles) {
    suspend fun capture(id: Long): List<CardStatusSnapshot> {
        val existing = db.reprocessDao().get(id)
        if (existing?.pending == true) return Json.decodeFromString(existing.snapshotJson)
        val current = db.cardDao().snapshotStatuses(id)
        val legacyFile = files.dir(id).resolve(LEGACY_FILE)
        // A completion marker prevents stale files from overriding newer study progress.
        // Invalid legacy files fail closed, before any deletion, rather than silently losing progress.
        val legacy: List<CardStatusSnapshot> = if (existing == null && legacyFile.isFile && db.documentDao().getById(id)?.status != pro.perfectproduct.cramin.data.db.DocStatus.READY)
            Json.decodeFromString(legacyFile.readText()) else emptyList()
        val unresolved: List<CardStatusSnapshot> = existing?.let { Json.decodeFromString(it.snapshotJson) } ?: emptyList()
        val snapshot = (unresolved.associateBy { it.lemmaKey to it.meaningKey } + current.associateBy { it.lemmaKey to it.meaningKey } + legacy.associateBy { it.lemmaKey to it.meaningKey }).values.toList()
        db.reprocessDao().put(ReprocessState(id, Json.encodeToString(snapshot), true))
        return snapshot
    }

    suspend fun complete(id: Long, unmatched: List<CardStatusSnapshot> = emptyList()) {
        db.reprocessDao().put(ReprocessState(id, Json.encodeToString(unmatched), false))
    }

    companion object { const val LEGACY_FILE = "status-snapshot.json" }
}
