package pro.perfectproduct.cramin.ui.study

import androidx.room.withTransaction
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pro.perfectproduct.cramin.app.AppContainer
import pro.perfectproduct.cramin.data.db.Direction
import pro.perfectproduct.cramin.data.repo.DeckKey
import pro.perfectproduct.cramin.data.repo.StudyCard
import pro.perfectproduct.cramin.study.DeckBuilder
import pro.perfectproduct.cramin.study.SessionEvent
import pro.perfectproduct.cramin.study.SessionState
import pro.perfectproduct.cramin.study.StudySessionMachine
import pro.perfectproduct.cramin.util.Lang
import pro.perfectproduct.cramin.util.Log

data class StudyUiState(
    val loading: Boolean = true,
    val cards: Map<Long, StudyCard> = emptyMap(),
    val session: SessionState? = null,
    val direction: Direction = Direction.SRC_FRONT,
    val autoSpeak: Boolean = false,
    val ttsRate: Float = 1f,
    val frontMs: Int = 3000,
    val backMs: Int = 3000,
    val ttsLangs: Set<Lang> = emptySet(),
    /** Есть сохранённая сессия: спросить «Продолжить с 35/96» или «Начать заново» (SPEC §9.6). */
    val resumeCandidate: SessionState? = null,
    val empty: Boolean = false,
    val migrationReset: Boolean = false,
) {
    val currentCard: StudyCard? get() = session?.currentCardId?.let { cards[it] }
    val srcLang: Lang? get() = cards.values.firstOrNull()?.lang
    val tgtLang: Lang? get() = cards.values.firstOrNull()?.targetLang
}

/**
 * Экран сессии (SPEC §9.6, §10.4): загрузка колоды, восстановление сохранённой сессии,
 * события в StudySessionMachine, автопроигрывание таймером и озвучка.
 */
class StudyViewModel(private val container: AppContainer, val deckKeyRaw: String, private val shuffleRequested: Boolean) : ViewModel() {
    private val _state = MutableStateFlow(StudyUiState())
    val state: StateFlow<StudyUiState> = _state
    private var machine: StudySessionMachine? = null
    private var autoplayJob: Job? = null
    private var freshIds: List<Long> = emptyList()
    private var sessionJob: Job? = null
    private val deckKey: DeckKey? = DeckKey.parse(deckKeyRaw)

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch { container.tts.available.collect { langs -> _state.update { it.copy(ttsLangs = langs) } } }
    }

    private suspend fun load() {
        val key = deckKey ?: run { _state.update { it.copy(loading = false, empty = true) }; return }
        val settings = container.settingsStore.current()
        val rawSaved = container.studyRepository.load(key.key)
        if (rawSaved?.contains("meaning-model-v4") == true) _state.update { it.copy(migrationReset = true) }
        val saved = rawSaved?.let { SessionState.fromJson(it) }
        val required = saved?.let { s -> (s.order + s.initialOrder + s.roundOrders.values.flatten()).distinct() }.orEmpty()
        val freshCards = when (key) {
            is DeckKey.Document -> container.cardRepository.deckCards(key.documentId, key.filter)
            is DeckKey.All -> container.cardRepository.sharedDeckCards(key.lang, key.targetLang)
        }
        freshIds = freshCards.map { it.id }
        val cards = if (key is DeckKey.All && required.isNotEmpty())
            container.cardRepository.sharedDeckCards(key.lang, key.targetLang, required, saved?.undoStack.orEmpty().associate { it.cardId to it.previousStatuses.orEmpty().keys.toList() }) else freshCards
        val direction = when (key) {
            is DeckKey.Document -> container.documentRepository.get(key.documentId)?.direction ?: settings.defaultDirection
            is DeckKey.All -> container.settingsStore.allDeckDirection(key.lang.code, key.targetLang.code).first() ?: settings.defaultDirection
        }
        _state.update {
            it.copy(
                cards = cards.associateBy { c -> c.id }, direction = direction, autoSpeak = settings.autoSpeak, ttsRate = settings.ttsRate,
                frontMs = settings.autoplayFrontMs, backMs = settings.autoplayBackMs,
            )
        }
        if (cards.isEmpty() && saved == null) {
            _state.update { it.copy(loading = false, empty = true) }
            return
        }
        val ids = freshIds
        // Сохранённая сессия продолжается по своему порядку, даже если часть карточек уже выучена
        // и выпала из фильтра колоды; годится, пока все её карточки существуют.
        val usable = saved?.takeIf { s -> s.order.isNotEmpty() && (s.position > 0 || s.canUndo) }?.let { s ->
            val missing = required.filter { id -> id !in _state.value.cards }
            if (missing.isEmpty()) return@let s
            val extra = if (key is DeckKey.All) emptyList() else container.cardRepository.cardsByIds(missing)
            if (extra.size != missing.size) return@let null
            _state.update { it.copy(cards = it.cards + extra.associateBy { c -> c.id }) }
            s
        }
        if (usable != null) {
            _state.update { it.copy(loading = false, resumeCandidate = usable) }
        } else {
            startFresh(ids)
        }
    }

    private suspend fun startFresh(ids: List<Long>) {
        if (ids.isEmpty()) {
            _state.update { it.copy(loading = false, empty = true, resumeCandidate = null) }
            return
        }
        val seed = if (shuffleRequested) System.nanoTime() else null
        val order = if (seed != null) DeckBuilder.shuffle(ids, seed) else ids
        install(SessionState(deckKey = deckKeyRaw, order = order, shuffleSeed = seed), ids)
    }

    private suspend fun install(initial: SessionState, fullOrder: List<Long>) {
        val key = deckKey ?: return
        val store = container.cardRepository.statusStore(key is DeckKey.All) { _state.value.cards[it] }
        val m = StudySessionMachine(initial, store,
            persist = { s -> container.studyRepository.save(key.key, s.toJson()) },
            fullOrder = fullOrder, transaction = { block -> container.db.withTransaction { block() } })
        container.studyRepository.save(key.key, initial.toJson())
        sessionJob?.cancel()
        machine = m
        _state.update { it.copy(loading = false, session = m.state.value, resumeCandidate = null) }
        sessionJob = viewModelScope.launch { m.state.collect { s -> _state.update { it.copy(session = s) }; syncAutoplay(s) } }

    }

    fun acknowledgeMigration() { _state.update { it.copy(migrationReset = false) } }

    fun resume() = viewModelScope.launch {
        val s = _state.value.resumeCandidate ?: return@launch
        install(s, s.initialOrder)
    }

    fun restartInsteadOfResume() { viewModelScope.launch { startFresh(_state.value.resumeCandidate?.initialOrder ?: freshIds) } }

    private var autoplayAtPointerDown = false
    fun interactionStart() {
        autoplayAtPointerDown = _state.value.session?.autoplay == true
        autoplayJob?.cancel()
        dispatch(SessionEvent.Interaction)
    }
    fun autoplayPointerClick() {
        // Root pointer-down already paused playback; a press on Pause must not restart it.
        if (!autoplayAtPointerDown) dispatch(SessionEvent.ToggleAutoplay)
    }

    fun dispatch(event: SessionEvent) = viewModelScope.launch {
        val before = _state.value.session
        machine?.dispatch(event)
        val after = _state.value.session ?: return@launch
        if (_state.value.autoSpeak && !after.autoplay && before != null && (before.position != after.position || before.isFlipped != after.isFlipped)) {
            speakCurrent()
        }
    }

    fun setDirection(direction: Direction) = viewModelScope.launch {
        _state.update { it.copy(direction = direction) }
        when (val key = deckKey) {
            is DeckKey.Document -> container.documentRepository.setDirection(key.documentId, direction)
            is DeckKey.All -> container.settingsStore.setAllDeckDirection(key.lang.code, key.targetLang.code, direction)
            null -> Unit
        }
    }

    fun setAutoSpeak(enabled: Boolean) = viewModelScope.launch {
        _state.update { it.copy(autoSpeak = enabled) }
        container.settingsStore.setAutoSpeak(enabled)
    }

    fun setIntervals(frontMs: Int, backMs: Int) = viewModelScope.launch {
        _state.update { it.copy(frontMs = frontMs, backMs = backMs) }
        container.settingsStore.setAutoplayIntervals(frontMs, backMs)
    }

    /** Перемешать заново: новый раунд с зерном. */
    fun reshuffle() {
        viewModelScope.launch {
            val ids = _state.value.session?.initialOrder ?: freshIds
            val seed = System.nanoTime()
            install(SessionState(deckKey = deckKeyRaw, order = DeckBuilder.shuffle(ids, seed), shuffleSeed = seed), ids)
        }
    }

    /** Озвучка текущей стороны: лицо — лемма/переводы, оборот — переводы без примеров (SPEC §9.6). */
    fun speakCurrent() {
        val s = _state.value
        val card = s.currentCard ?: return
        val session = s.session ?: return
        val frontIsSource = s.direction == Direction.SRC_FRONT
        val showSource = frontIsSource != session.isFlipped
        val (text, lang) = if (showSource) (card.lemmaVocalized ?: card.lemma) to card.lang else card.visibleSenses.joinToString("; ") { it.translation } to card.targetLang
        if (lang in s.ttsLangs) container.tts.speak(text, lang, s.ttsRate)
    }

    fun speak(text: String, lang: Lang) {
        if (lang in _state.value.ttsLangs) container.tts.speak(text, lang, _state.value.ttsRate)
    }

    fun toggleStar(card: StudyCard) = viewModelScope.launch {
        container.cardRepository.setStarred(card.id, !card.starred)
        _state.update { it.copy(cards = it.cards + (card.id to card.copy(starred = !card.starred))) }
    }

    private fun syncAutoplay(s: SessionState) {
        if (s.autoplay && autoplayJob?.isActive != true) {
            autoplayJob = viewModelScope.launch {
                while (_state.value.session?.autoplay == true) {
                    val current = _state.value.session ?: break
                    if (_state.value.autoSpeak) speakCurrent()
                    delay((if (current.isFlipped) _state.value.backMs else _state.value.frontMs).toLong().coerceAtLeast(500L))
                    if (_state.value.session?.autoplay != true) break
                    machine?.dispatch(SessionEvent.Tick)
                }
            }
        } else if (!s.autoplay) {
            autoplayJob?.cancel()
            autoplayJob = null
        }
    }

    override fun onCleared() {
        autoplayJob?.cancel()
        container.tts.stop()
        Log.i("Study", "session closed deck=$deckKeyRaw pos=${_state.value.session?.position}")
    }
}
