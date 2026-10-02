package pro.perfectproduct.cramin.study

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import pro.perfectproduct.cramin.data.db.CardStatus

/** Что сделал свайп или автопроигрывание с карточкой; нужно для отмены. */
@Serializable
enum class UndoAction { KNOWN, LEARNING, SKIP }

@Serializable
data class UndoPosition(val round: Int, val position: Int, val known: Int, val learning: Int)

@Serializable
data class UndoEntry(
    val cardId: Long,
    val previousStatus: CardStatus?,
    val action: UndoAction,
    val previousStatuses: Map<Long, CardStatus>? = null,
    val before: UndoPosition? = null,
)

/**
 * Состояние сессии (SPEC §10.4). Сериализуется в StudySession.stateJson после каждого события.
 * `finished` — раунд закончился: показана сводка «Вы знаете K из N».
 */
@Serializable
data class SessionState(
    val deckKey: String,
    val order: List<Long>,
    val position: Int = 0,
    val round: Int = 1,
    val knownThisRound: Int = 0,
    val learningThisRound: Int = 0,
    val learningIdsThisRound: List<Long> = emptyList(),
    val undoStack: List<UndoEntry> = emptyList(),
    val isFlipped: Boolean = false,
    val autoplay: Boolean = false,
    val shuffleSeed: Long? = null,
    val roundSize: Int = order.size,
    val initialOrder: List<Long> = order,
    val roundOrders: Map<Int, List<Long>> = mapOf(round to order),
    val baseLearningIds: Map<Int, List<Long>> = emptyMap(),
) {
    val finished: Boolean get() = position >= order.size
    val currentCardId: Long? get() = order.getOrNull(position)
    val total: Int get() = order.size
    val canUndo: Boolean get() = undoStack.isNotEmpty()

    fun toJson(): String = json.encodeToString(this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun fromJson(text: String): SessionState? = runCatching {
            val state = json.decodeFromString<SessionState>(text)
            // Legacy history cannot prove the individual statuses of a shared group.
            if (state.undoStack.any { it.before == null || (it.action != UndoAction.SKIP && it.previousStatuses == null) })
                state.copy(undoStack = emptyList(), autoplay = false, baseLearningIds = mapOf(state.round to state.learningIdsThisRound))
            else state.copy(autoplay = false)
        }.getOrNull()
    }
}

sealed interface SessionEvent {
    data object Flip : SessionEvent
    data object SwipeRight : SessionEvent
    data object SwipeLeft : SessionEvent
    data object Undo : SessionEvent
    data object ToggleAutoplay : SessionEvent
    data object Tick : SessionEvent
    data object RestartAll : SessionEvent
    data object RepeatLearning : SessionEvent
}

/** Статусы карточек в БД (через репозиторий); фейк в тестах. */
interface CardStatusStore {
    suspend fun get(cardId: Long): CardStatus?
    suspend fun set(cardId: Long, status: CardStatus)
    suspend fun snapshot(cardId: Long): Map<Long, CardStatus> = get(cardId)?.let { mapOf(cardId to it) }.orEmpty()
    suspend fun restore(snapshot: Map<Long, CardStatus>) { snapshot.forEach { (id, status) -> set(id, status) } }
}

/**
 * Машина сессии (SPEC §10.4): чистый Kotlin без Android. Свайп пишет статус через [statuses],
 * Undo восстанавливает прежний статус из стека, состояние сохраняется через [persist] после каждого события.
 * Автопроигрывание: по Tick — переворот, затем переход к следующей карточке без изменения статуса;
 * любой жест пользователя ставит его на паузу.
 */
class StudySessionMachine(
    initial: SessionState,
    private val statuses: CardStatusStore,
    private val persist: suspend (SessionState) -> Unit,
    /** Полный список карточек для «Начать заново» (перемешивание с новым зерном). */
    private val fullOrder: List<Long> = initial.initialOrder,
    private val transaction: suspend (suspend () -> Unit) -> Unit = { it() },
    private val seedSource: () -> Long = { System.nanoTime() },
) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<SessionState> = _state.asStateFlow()
    private val mutex = Mutex()

    suspend fun dispatch(event: SessionEvent) = mutex.withLock {
        val s = _state.value
        var committed = s
        transaction {
            val next: SessionState = when (event) {
                SessionEvent.Flip -> if (s.finished) s else s.copy(isFlipped = !s.isFlipped, autoplay = false)
                SessionEvent.SwipeRight -> sort(s, CardStatus.KNOWN)
                SessionEvent.SwipeLeft -> sort(s, CardStatus.LEARNING)
                SessionEvent.Undo -> undo(s)
                SessionEvent.ToggleAutoplay -> if (s.finished) s else s.copy(autoplay = !s.autoplay)
                SessionEvent.Tick -> tick(s)
                SessionEvent.RestartAll -> restart(s)
                SessionEvent.RepeatLearning -> repeatLearning(s)
            }
            if (next != s) {
                persist(next)
                committed = next
            }
        }
        _state.value = committed
    }

    private suspend fun sort(s: SessionState, status: CardStatus): SessionState {
        val id = s.currentCardId ?: return s
        val previous = statuses.snapshot(id)
        // Apply precisely the IDs captured in the same transaction.
        statuses.restore(previous.mapValues { status })
        val entry = UndoEntry(id, null, if (status == CardStatus.KNOWN) UndoAction.KNOWN else UndoAction.LEARNING,
            previous, UndoPosition(s.round, s.position, s.knownThisRound, s.learningThisRound))
        return s.copy(
            position = s.position + 1,
            knownThisRound = s.knownThisRound + if (status == CardStatus.KNOWN) 1 else 0,
            learningThisRound = s.learningThisRound + if (status == CardStatus.LEARNING) 1 else 0,
            learningIdsThisRound = if (status == CardStatus.LEARNING) s.learningIdsThisRound + id else s.learningIdsThisRound,
            undoStack = s.undoStack + entry,
            isFlipped = false,
            autoplay = false,
        )
    }

    private suspend fun undo(s: SessionState): SessionState {
        val entry = s.undoStack.lastOrNull() ?: return s.copy(autoplay = false)
        if (entry.before == null || (entry.action != UndoAction.SKIP && entry.previousStatuses == null))
            return s.copy(undoStack = emptyList(), autoplay = false)
        entry.previousStatuses?.let { statuses.restore(it) }
        val before = entry.before
        val remaining = s.undoStack.dropLast(1)
        val order = s.roundOrders[before.round] ?: return s.copy(undoStack = emptyList(), autoplay = false)
        return s.copy(
            order = order, roundSize = order.size, round = before.round, position = before.position,
            knownThisRound = before.known, learningThisRound = before.learning,
            learningIdsThisRound = s.baseLearningIds[before.round].orEmpty() + remaining.filter { it.before?.round == before.round && it.action == UndoAction.LEARNING }.map { it.cardId },
            undoStack = remaining, isFlipped = false, autoplay = false,
        )
    }

    private fun tick(s: SessionState): SessionState {
        if (!s.autoplay || s.finished) return s
        if (!s.isFlipped) return s.copy(isFlipped = true)
        val id = s.currentCardId ?: return s
        val next = s.copy(position = s.position + 1, isFlipped = false, undoStack = s.undoStack + UndoEntry(id, null, UndoAction.SKIP, before = UndoPosition(s.round, s.position, s.knownThisRound, s.learningThisRound)))
        return if (next.finished) next.copy(autoplay = false) else next
    }

    private fun restart(s: SessionState): SessionState {
        val seed = s.shuffleSeed?.let { seedSource() }
        val order = if (seed != null) DeckBuilder.shuffle(fullOrder, seed) else fullOrder
        return SessionState(deckKey = s.deckKey, order = order, round = 1, shuffleSeed = seed, roundSize = order.size)
    }

    private fun repeatLearning(s: SessionState): SessionState {
        if (s.learningIdsThisRound.isEmpty()) return s.copy(autoplay = false)
        val order = s.learningIdsThisRound
        return SessionState(deckKey = s.deckKey, order = order, round = s.round + 1, shuffleSeed = s.shuffleSeed, roundSize = order.size, undoStack = s.undoStack, initialOrder = s.initialOrder, roundOrders = s.roundOrders + (s.round + 1 to order), baseLearningIds = s.baseLearningIds)
    }
}

/** Порядок колоды (SPEC §10.3): по первому появлению в тексте или перемешанный с зерном. */
object DeckBuilder {
    fun order(cardIds: List<Long>, shuffle: Boolean, seed: Long): List<Long> = if (shuffle) shuffle(cardIds, seed) else cardIds

    fun shuffle(cardIds: List<Long>, seed: Long): List<Long> = cardIds.shuffled(kotlin.random.Random(seed))
}
