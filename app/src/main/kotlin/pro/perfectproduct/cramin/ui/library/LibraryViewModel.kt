package pro.perfectproduct.cramin.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pro.perfectproduct.cramin.R
import pro.perfectproduct.cramin.app.AppContainer
import pro.perfectproduct.cramin.data.db.DocStatus
import pro.perfectproduct.cramin.data.db.DocumentWithCounts

/** Вкладки-чипы библиотеки (SPEC §9.1). */
enum class LibraryFilter(val labelRes: Int) {
    ALL(R.string.library_tab_all),
    IN_PROGRESS(R.string.library_tab_in_progress),
    LEARNED(R.string.library_tab_learned),
}

class LibraryViewModel(private val container: AppContainer) : ViewModel() {
    private val _filter = MutableStateFlow(LibraryFilter.ALL)
    val filter: StateFlow<LibraryFilter> = _filter
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    val items: StateFlow<List<DocumentWithCounts>> = combine(container.documentRepository.observeLibrary(), _filter, _query) { docs, f, q ->
        docs.filter { row ->
            val d = row.document
            val byFilter = when (f) {
                LibraryFilter.ALL -> true
                LibraryFilter.IN_PROGRESS -> d.status == DocStatus.READY && row.knownCount < row.cardCount
                LibraryFilter.LEARNED -> d.status == DocStatus.READY && row.cardCount > 0 && row.knownCount == row.cardCount
            }
            byFilter && (q.isBlank() || d.title.contains(q.trim(), ignoreCase = true))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val hasReady: StateFlow<Boolean> = container.documentRepository.observeReadyCount().map { it > 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setFilter(f: LibraryFilter) { _filter.value = f }
    fun setQuery(q: String) { _query.value = q }

    fun rename(id: Long, title: String) = viewModelScope.launch { if (title.isNotBlank()) container.documentRepository.rename(id, title) }

    fun delete(id: Long) = viewModelScope.launch {
        container.processScheduler.cancel(id)
        container.documentRepository.delete(id)
    }

    /** Повтор после ошибки продолжает с места сбоя (SPEC U4). */
    fun retry(id: Long) = viewModelScope.launch {
        container.documentRepository.requeue(id)
        container.processScheduler.enqueue(id)
    }

    fun reprocess(id: Long) = viewModelScope.launch {
        container.processScheduler.cancel(id)
        container.documentRepository.prepareReprocess(id)
        container.processScheduler.enqueue(id)
    }
}
