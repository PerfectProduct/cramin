package pro.perfectproduct.cramin.ui.study

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
import pro.perfectproduct.cramin.data.db.CardStatus
import pro.perfectproduct.cramin.data.db.Direction
import pro.perfectproduct.cramin.data.repo.DeckKey
import pro.perfectproduct.cramin.data.repo.StudyCard
import pro.perfectproduct.cramin.study.CardStatusStore
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
    private val deckKey: DeckKey? = DeckKey.parse(deckKeyRaw)

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch { container.tts.available.collect { langs -> _state.update { it.copy(ttsLangs = langs) } } }
    }

    private suspend fun load() {
        val key = deckKey ?: run { _state.update { it.copy(loading = false, empty = true) }; return }
        val settings = container.settingsStore.current()
        val cards = when (key) {
            is DeckKey.Document -> container.cardRepository.deckCards(key.documentId, key.filter)
            is DeckKey.All -> container.cardRepository.sharedDeckCards(key.lang, key.targetLang)
        }
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
        if (cards.isEmpty()) {
            _state.update { it.copy(loading = false, empty = true) }
            return
        }
        val ids = cards.map { it.id }
        val saved = container.studyRepository.load(key.key)?.let { SessionState.fromJson(it) }
        // Сохранённая сессия продолжается по своему порядку, даже если часть карточек уже выучена
        // и выпала из фильтра колоды; годится, пока все её карточки существуют.
        val usable = saved?.takeIf { s -> s.order.isNotEmpty() && !s.finished && s.position > 0 }?.let { s ->
            val missing = s.order.filter { id -> id !in _state.value.cards }
            if (missing.isEmpty()) return@let s
            val extra = container.cardRepository.cardsByIds(missing)
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

    private fun startFresh(ids: List<Long>) {
        val seed = if (shuffleRequested) System.nanoTime() else null
        val order = if (seed != null) DeckBuilder.shuffle(ids, seed) else ids
        install(SessionState(deckKey = deckKeyRaw, order = order, shuffleSeed = seed), ids)
    }

    private fun install(initial: SessionState, fullOrder: List<Long>) {
        val key = deckKey ?: return
        val store = object : CardStatusStore {
            override suspend fun get(cardId: Long): CardStatus? = container.cardRepository.getStatus(cardId)
            override suspend fun set(cardId: Long, status: CardStatus) {
                val card = _state.value.cards[cardId]
                // В общей колоде свайп меняет статус у всех дубликатов (SPEC §10.6).
                if (key is DeckKey.All && card != null) container.cardRepository.setStatusForLemma(card.lang, card.targetLang, card.lemmaKey, status)
                else container.cardRepository.setStatus(cardId, status)
            }
        }
        val m = StudySessionMachine(initial, store, persist = { s -> container.studyRepository.save(key.key, s.toJson()) }, fullOrder = fullOrder)
        machine = m
        _state.update { it.copy(loading = false, session = m.state.value, resumeCandidate = null) }
        viewModelScope.launch { m.state.collect { s -> _state.update { it.copy(session = s) }; syncAutoplay(s) } }
        viewModelScope.launch { container.studyRepository.save(key.key, initial.toJson()) }
    }

    fun resume() {
        val s = _state.value.resumeCandidate ?: return
        install(s, _state.value.cards.keys.toList())
    }

    fun restartInsteadOfResume() = startFresh(_state.value.cards.keys.toList())

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
        val ids = _state.value.cards.keys.toList()
        val seed = System.nanoTime()
        install(SessionState(deckKey = deckKeyRaw, order = DeckBuilder.shuffle(ids, seed), shuffleSeed = seed), ids)
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
