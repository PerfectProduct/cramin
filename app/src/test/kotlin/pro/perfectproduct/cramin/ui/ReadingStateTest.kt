package pro.perfectproduct.cramin.ui

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import pro.perfectproduct.cramin.data.db.*
import pro.perfectproduct.cramin.ui.document.*

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingStateTest {
    private fun sentence(id: Long = 1) = SentenceEntity(id = id, documentId = id, idx = 0, paragraphIdx = 0, text = "Source $id", segmentId = 1)

    @Test fun delayedSourceDoesNotBecomeEmptyAndDoesNotWaitForTranslation() = runTest {
        val source = MutableSharedFlow<List<SentenceEntity>>()
        val translation = MutableSharedFlow<List<SegmentEntity>>()
        val spans = MutableSharedFlow<List<OccurrenceRow>>()
        val states = mutableListOf<ReadingState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { readingStates(source, translation, spans).toList(states) }
        runCurrent()
        assertTrue(states.last().loading)
        advanceTimeBy(2000); runCurrent()
        assertTrue(states.all { it.loading })
        source.emit(listOf(sentence())); runCurrent()
        assertFalse(states.last().loading)
        assertEquals("Source 1", states.last().paragraphs[0].segments[0].sentences[0].text)
        translation.emit(listOf(SegmentEntity(1, 1, 0, 0, "Перевод"))); runCurrent()
        spans.emit(emptyList()); runCurrent()
        assertTrue(states.none { !it.loading && it.paragraphs.isEmpty() })
        assertEquals("Перевод", states.last().paragraphs[0].segments[0].translation)
    }

    @Test fun trueEmptyAndDatabaseFailureAreDistinctAndCancellationPropagates() = runTest {
        val empty = readingStates(flowOf(emptyList()), flowOf(emptyList()), flowOf(emptyList())).toList().last()
        assertFalse(empty.loading); assertFalse(empty.error); assertTrue(empty.paragraphs.isEmpty())
        val failure = readingStates(flow { throw java.io.IOException() }, flowOf(emptyList()), flowOf(emptyList())).toList().last()
        assertFalse(failure.loading); assertTrue(failure.error)
        val retained = readingStates(flow { emit(listOf(sentence())); yield(); throw java.io.IOException() }, flowOf(emptyList()), flowOf(emptyList())).toList().last()
        assertTrue(retained.error); assertFalse(retained.paragraphs.isEmpty())
        val job = backgroundScope.launch { readingStates(flow { awaitCancellation() }, flowOf(emptyList()), flowOf(emptyList())).collect() }
        runCurrent(); job.cancelAndJoin(); assertTrue(job.isCancelled)
    }

    @Test fun returningToTabRetainsSingleSubscriptionAndNewDocumentStartsLoading() = runTest {
        var subscriptions = 0
        val source = MutableSharedFlow<List<SentenceEntity>>()
        val cached = readingStates(source.onStart { subscriptions++ }, flowOf(emptyList()), flowOf(emptyList()))
            .stateIn(backgroundScope, SharingStarted.Lazily, ReadingState())
        val first = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { cached.collect() }
        runCurrent(); source.emit(listOf(sentence())); runCurrent(); first.cancel()
        advanceTimeBy(6000); runCurrent()
        val second = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { cached.collect() }
        runCurrent(); assertEquals(1, subscriptions); assertFalse(cached.value.loading)
        val next = readingStates(flow { awaitCancellation() }, flowOf(emptyList()), flowOf(emptyList()))
            .stateIn(backgroundScope, SharingStarted.Lazily, ReadingState())
        assertTrue(next.value.loading); assertTrue(next.value.paragraphs.isEmpty())
        second.cancel()
    }
}
