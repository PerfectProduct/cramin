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
data class UndoEntry(val cardId: Long, val previousStatus: CardStatus?, val action: UndoAction)

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
) {
    val finished: Boolean get() = position >= order.size
    val currentCardId: Long? get() = order.getOrNull(position)
    val total: Int get() = order.size
    val canUndo: Boolean get() = undoStack.isNotEmpty()

    fun toJson(): String = json.encodeToString(this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun fromJson(text: String): SessionState? = runCatching { json.decodeFromString<SessionState>(text) }.getOrNull()
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
    private val fullOrder: List<Long> = initial.order,
    private val seedSource: () -> Long = { System.nanoTime() },
) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<SessionState> = _state.asStateFlow()
    private val mutex = Mutex()

    suspend fun dispatch(event: SessionEvent) = mutex.withLock {
        val s = _state.value
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
            _state.value = next
            persist(next)
        }
    }

    private suspend fun sort(s: SessionState, status: CardStatus): SessionState {
        val id = s.currentCardId ?: return s
        val prev = statuses.get(id)
        statuses.set(id, status)
        val entry = UndoEntry(id, prev, if (status == CardStatus.KNOWN) UndoAction.KNOWN else UndoAction.LEARNING)
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
        if (entry.action != UndoAction.SKIP && entry.previousStatus != null) statuses.set(entry.cardId, entry.previousStatus)
        return s.copy(
            position = (s.position - 1).coerceAtLeast(0),
            knownThisRound = s.knownThisRound - if (entry.action == UndoAction.KNOWN) 1 else 0,
            learningThisRound = s.learningThisRound - if (entry.action == UndoAction.LEARNING) 1 else 0,
            learningIdsThisRound = if (entry.action == UndoAction.LEARNING) s.learningIdsThisRound.dropLast(1) else s.learningIdsThisRound,
            undoStack = s.undoStack.dropLast(1),
            isFlipped = false,
            autoplay = false,
        )
    }

    private fun tick(s: SessionState): SessionState {
        if (!s.autoplay || s.finished) return s
        if (!s.isFlipped) return s.copy(isFlipped = true)
        val id = s.currentCardId ?: return s
        val next = s.copy(position = s.position + 1, isFlipped = false, undoStack = s.undoStack + UndoEntry(id, null, UndoAction.SKIP))
        return if (next.finished) next.copy(autoplay = false) else next
    }

    private fun restart(s: SessionState): SessionState {
        val seed = s.shuffleSeed?.let { seedSource() }
        val order = if (seed != null) DeckBuilder.shuffle(fullOrder, seed) else fullOrder
        return SessionState(deckKey = s.deckKey, order = order, round = 1, shuffleSeed = seed, roundSize = order.size)
    }

    private fun repeatLearning(s: SessionState): SessionState {
        if (s.learningIdsThisRound.isEmpty()) return restart(s)
        val order = s.learningIdsThisRound
        return SessionState(deckKey = s.deckKey, order = order, round = s.round + 1, shuffleSeed = s.shuffleSeed, roundSize = order.size)
    }
}

/** Порядок колоды (SPEC §10.3): по первому появлению в тексте или перемешанный с зерном. */
object DeckBuilder {
    fun order(cardIds: List<Long>, shuffle: Boolean, seed: Long): List<Long> = if (shuffle) shuffle(cardIds, seed) else cardIds

    fun shuffle(cardIds: List<Long>, seed: Long): List<Long> = cardIds.shuffled(kotlin.random.Random(seed))
}
