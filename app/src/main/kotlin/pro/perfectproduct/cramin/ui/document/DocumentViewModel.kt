package pro.perfectproduct.cramin.ui.document

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import pro.perfectproduct.cramin.app.AppContainer
import pro.perfectproduct.cramin.data.db.CardCounts
import pro.perfectproduct.cramin.data.db.CardStatus
import pro.perfectproduct.cramin.data.db.Direction
import pro.perfectproduct.cramin.data.db.DocumentWithCounts
import pro.perfectproduct.cramin.data.db.OccurrenceRow
import pro.perfectproduct.cramin.data.db.SegmentEntity
import pro.perfectproduct.cramin.data.db.SentenceEntity
import pro.perfectproduct.cramin.data.repo.DeckFilter
import pro.perfectproduct.cramin.data.repo.DeckKey
import pro.perfectproduct.cramin.data.repo.StudyCard
import pro.perfectproduct.cramin.util.Lang

/** Подчёркиваемый фрагмент предложения: смещения и карточка (SPEC §9.4). */
data class WordSpan(val start: Int, val end: Int, val cardId: Long)

data class TextSentence(val idx: Int, val text: String, val spans: List<WordSpan>)

/** Единица отображения вкладки «Текст» — сегмент перевода с его предложениями. */
data class TextSegment(val key: String, val sentences: List<TextSentence>, val translation: String?)

data class TextParagraph(val paragraphIdx: Int, val segments: List<TextSegment>)

enum class TextViewMode { PAIRS, SOURCE_ONLY, TARGET_ONLY }

class DocumentViewModel(private val container: AppContainer, val documentId: Long) : ViewModel() {
    val document: StateFlow<DocumentWithCounts?> = container.documentRepository.observeDocumentWithCounts(documentId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val counts: StateFlow<CardCounts> = container.cardRepository.observeCounts(documentId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CardCounts(0, 0, 0, 0, 0))

    val paragraphs: StateFlow<List<TextParagraph>> = combine(
        container.db.sentenceDao().observeByDocument(documentId),
        container.db.segmentDao().observeByDocument(documentId),
        container.cardRepository.observeOccurrences(documentId),
    ) { sentences, segments, occurrences -> buildParagraphs(sentences, segments, occurrences) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _viewMode = MutableStateFlow(TextViewMode.PAIRS)
    val viewMode: StateFlow<TextViewMode> = _viewMode
    fun setViewMode(mode: TextViewMode) { _viewMode.value = mode }

    private val _deckFilter = MutableStateFlow(DeckFilter.UNLEARNED)
    val deckFilter: StateFlow<DeckFilter> = _deckFilter
    fun setDeckFilter(f: DeckFilter) { _deckFilter.value = f }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val canResume = deckFilter.flatMapLatest { filter ->
        container.studyRepository.observeResumable(DeckKey.Document(documentId, filter).key)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle
    fun setShuffle(v: Boolean) { _shuffle.value = v }

    /** Направление: у документа своё, по умолчанию — из настроек (SPEC §9.5). */
    val direction: StateFlow<Direction> = combine(document, container.settingsStore.settings) { d, s -> d?.document?.direction ?: s.defaultDirection }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Direction.SRC_FRONT)

    fun setDirection(direction: Direction) = viewModelScope.launch { container.documentRepository.setDirection(documentId, direction) }

    val ttsAvailable: StateFlow<Set<Lang>> get() = container.tts.available
    fun speak(text: String, lang: Lang) = viewModelScope.launch { container.tts.speak(text, lang, container.settingsStore.current().ttsRate) }

    fun deckKey(): String = DeckKey.Document(documentId, _deckFilter.value).key

    suspend fun card(cardId: Long): StudyCard? = container.cardRepository.card(cardId)

    fun setStatus(cardId: Long, status: CardStatus) = viewModelScope.launch { container.cardRepository.setStatus(cardId, status) }
    fun setStarred(cardId: Long, starred: Boolean) = viewModelScope.launch { container.cardRepository.setStarred(cardId, starred) }

    fun rename(title: String) = viewModelScope.launch { if (title.isNotBlank()) container.documentRepository.rename(documentId, title) }
    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        container.processScheduler.cancel(documentId)
        container.documentRepository.delete(documentId)
        onDone()
    }

    fun copyDiagnostics(context: android.content.Context) = viewModelScope.launch {
        val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            pro.perfectproduct.cramin.pipeline.DocumentStateSummary.copyText(container.db, documentId, container.stoplists,
                pro.perfectproduct.cramin.BuildConfig.VERSION_NAME, android.os.Build.VERSION.SDK_INT, container.files)
        }
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Cramin", text))
    }

    fun retry() = viewModelScope.launch {
        container.documentRepository.requeue(documentId)
        container.processScheduler.enqueue(documentId)
    }

    private val reprocessMutex = kotlinx.coroutines.sync.Mutex()

    fun retryLocalConsolidation() = container.appScope.launch {
        reprocessMutex.withLock {
            container.processScheduler.cancelAndAwait(documentId)
            container.documentRepository.requeue(documentId)
            container.processScheduler.enqueue(documentId, localConsolidationOnly = true)
        }
    }

    fun resumeConsolidationWithApi() = container.appScope.launch {
        reprocessMutex.withLock {
            container.processScheduler.cancelAndAwait(documentId)
            container.documentRepository.requeue(documentId)
            container.processScheduler.enqueue(documentId, consolidationOnly = true)
        }
    }

    fun reprocess() = container.appScope.launch {
        reprocessMutex.withLock {
            container.processScheduler.cancelAndAwait(documentId)
            container.documentRepository.prepareReprocess(documentId)
            container.processScheduler.enqueue(documentId)
        }
    }

    fun chooseSourceLang(lang: Lang) = viewModelScope.launch {
        container.documentRepository.setSourceLang(documentId, lang)
        container.documentRepository.requeue(documentId)
        container.processScheduler.enqueue(documentId)
    }

    fun chooseTargetLang(lang: Lang) = viewModelScope.launch {
        container.documentRepository.setTargetLang(documentId, lang)
        container.documentRepository.requeue(documentId)
        container.processScheduler.enqueue(documentId)
    }

    companion object {
        fun buildParagraphs(sentences: List<SentenceEntity>, segments: List<SegmentEntity>, occurrences: List<OccurrenceRow>): List<TextParagraph> {
            if (sentences.isEmpty()) return emptyList()
            val spansBySentence = HashMap<Long, MutableList<WordSpan>>()
            for (o in occurrences) {
                // Legacy unassigned occurrences are retained, but cannot claim a particular meaning.
                if (o.occurrence.senseId == null) continue
                val s = o.occurrence.start ?: continue
                val e = o.occurrence.end ?: continue
                spansBySentence.getOrPut(o.occurrence.sentenceId) { mutableListOf() }.add(WordSpan(s, e, o.occurrence.cardId))
            }
            val segmentById = segments.associateBy { it.id }
            val out = ArrayList<TextParagraph>()
            for ((pIdx, group) in sentences.groupBy { it.paragraphIdx }.toSortedMap()) {
                val segs = ArrayList<TextSegment>()
                var i = 0
                while (i < group.size) {
                    val s = group[i]
                    val seg = s.segmentId?.let { segmentById[it] }
                    val members = ArrayList<SentenceEntity>()
                    if (seg == null) {
                        members += s
                        i++
                    } else {
                        while (i < group.size && group[i].segmentId == seg.id) {
                            members += group[i]
                            i++
                        }
                    }
                    segs += TextSegment(
                        key = seg?.let { "seg-${it.id}" } ?: "sent-${s.id}",
                        sentences = members.map { m -> TextSentence(m.idx, m.text, spansBySentence[m.id].orEmpty().sortedBy { it.start }.dedupe()) },
                        translation = seg?.translation,
                    )
                }
                out += TextParagraph(pIdx, segs)
            }
            return out
        }

        /** Пересекающиеся диапазоны (две карточки на одном месте) — оставляем первый. */
        private fun List<WordSpan>.dedupe(): List<WordSpan> {
            val out = ArrayList<WordSpan>()
            var lastEnd = -1
            for (s in this) {
                if (s.start < lastEnd) continue
                out += s
                lastEnd = s.end
            }
            return out
        }
    }
}
