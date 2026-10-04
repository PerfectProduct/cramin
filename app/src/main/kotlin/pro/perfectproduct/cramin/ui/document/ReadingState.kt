package pro.perfectproduct.cramin.ui.document

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import pro.perfectproduct.cramin.data.db.OccurrenceRow
import pro.perfectproduct.cramin.data.db.SegmentEntity
import pro.perfectproduct.cramin.data.db.SentenceEntity

/** Null source data means no database result yet; an empty result is a different state. */
data class ReadingState(
    val loading: Boolean = true,
    val paragraphs: List<TextParagraph> = emptyList(),
    val error: Boolean = false,
)

private data class ReadingPart<T>(val data: List<T>? = null, val error: Boolean = false)

private fun <T> Flow<List<T>>.readingPart(): Flow<ReadingPart<T>> = flow {
    var last: List<T>? = null
    emit(ReadingPart<T>())
    this@readingPart.map {
        last = it
        ReadingPart(it)
    }.catch {
        // Keep loaded content if a query fails; Flow.catch preserves cancellation.
        emit(ReadingPart(last, error = true))
    }.collect { emit(it) }
}

internal fun readingStates(
    sentences: Flow<List<SentenceEntity>>,
    segments: Flow<List<SegmentEntity>>,
    occurrences: Flow<List<OccurrenceRow>>,
): Flow<ReadingState> = combine(sentences.readingPart(), segments.readingPart(), occurrences.readingPart()) { source, translations, spans ->
    ReadingState(
        loading = source.data == null && !source.error,
        paragraphs = DocumentViewModel.buildParagraphs(source.data.orEmpty(), translations.data.orEmpty(), spans.data.orEmpty()),
        error = source.error || translations.error || spans.error,
    )
}
