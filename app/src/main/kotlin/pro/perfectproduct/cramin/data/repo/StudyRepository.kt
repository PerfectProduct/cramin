package pro.perfectproduct.cramin.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import pro.perfectproduct.cramin.study.SessionState
import pro.perfectproduct.cramin.data.db.CraminDatabase
import pro.perfectproduct.cramin.data.db.StudySessionEntity
import pro.perfectproduct.cramin.util.Clock

/** Сохранённое состояние сессий (SPEC §10.4): таблица StudySession, по одной строке на ключ колоды. */
class StudyRepository(
    private val db: CraminDatabase,
    private val clock: Clock,
) {
    private val dao get() = db.studySessionDao()

    suspend fun load(deckKey: String): String? = dao.get(deckKey)?.stateJson

    fun observe(deckKey: String): Flow<StudySessionEntity?> = dao.observe(deckKey)

    fun observeResumable(deckKey: String): Flow<Boolean> = dao.observe(deckKey).map { row ->
        row?.stateJson?.let { SessionState.fromJson(it) }?.let { it.order.isNotEmpty() && (it.position > 0 || it.canUndo) } == true
    }

    suspend fun save(deckKey: String, stateJson: String) =
        dao.upsert(StudySessionEntity(deckKey = deckKey, stateJson = stateJson, updatedAt = clock.now()))

    suspend fun clear(deckKey: String) = dao.delete(deckKey)
}
