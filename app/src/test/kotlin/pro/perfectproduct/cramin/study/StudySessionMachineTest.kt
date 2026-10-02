package pro.perfectproduct.cramin.study

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.perfectproduct.cramin.data.db.CardStatus

class StudySessionMachineTest {
    private class FakeStatuses(initial: Map<Long, CardStatus>) : CardStatusStore {
        val map = initial.toMutableMap()
        val writes = ArrayList<Pair<Long, CardStatus>>()
        override suspend fun get(cardId: Long) = map[cardId]
        override suspend fun set(cardId: Long, status: CardStatus) {
            map[cardId] = status
            writes += cardId to status
        }
    }

    private val ids = listOf(1L, 2L, 3L, 4L)
    private val persisted = ArrayList<String>()

    private fun machine(statuses: FakeStatuses = FakeStatuses(ids.associateWith { CardStatus.NEW }), state: SessionState = SessionState("doc:1:all", ids)) =
        StudySessionMachine(state, statuses, persist = { persisted += it.toJson() }, fullOrder = ids, seedSource = { 42L })

    @Test
    fun pointerDownPausesWithoutChangingCardOrHistory() = runTest {
        val statuses = FakeStatuses(ids.associateWith { CardStatus.NEW })
        val m = machine(statuses)
        m.dispatch(SessionEvent.ToggleAutoplay)
        val before = m.state.value
        m.dispatch(SessionEvent.Interaction)
        assertEquals(before.copy(autoplay = false), m.state.value)
        m.dispatch(SessionEvent.Tick)
        assertEquals(before.copy(autoplay = false), m.state.value)
        assertTrue(statuses.writes.isEmpty())
    }

    @Test
    fun swipesUpdateCountersStatusesAndPosition() = runTest {
        val statuses = FakeStatuses(ids.associateWith { CardStatus.NEW })
        val m = machine(statuses)
        m.dispatch(SessionEvent.Flip)
        assertTrue(m.state.value.isFlipped)
        m.dispatch(SessionEvent.SwipeRight)
        m.dispatch(SessionEvent.SwipeLeft)
        val s = m.state.value
        assertEquals(2, s.position)
        assertEquals(1, s.knownThisRound)
        assertEquals(1, s.learningThisRound)
        assertEquals(listOf(2L), s.learningIdsThisRound)
        assertFalse(s.isFlipped)
        assertEquals(CardStatus.KNOWN, statuses.map[1L])
        assertEquals(CardStatus.LEARNING, statuses.map[2L])
        assertEquals(3L, s.currentCardId)
        assertEquals(3, persisted.size)
    }

    @Test
    fun roundEndsAndRepeatLearningBuildsNewRound() = runTest {
        val m = machine()
        m.dispatch(SessionEvent.SwipeLeft)
        m.dispatch(SessionEvent.SwipeRight)
        m.dispatch(SessionEvent.SwipeLeft)
        m.dispatch(SessionEvent.SwipeRight)
        val end = m.state.value
        assertTrue(end.finished)
        assertNull(end.currentCardId)
        assertEquals(2, end.knownThisRound)
        assertEquals(4, end.total)
        m.dispatch(SessionEvent.RepeatLearning)
        val r2 = m.state.value
        assertEquals(2, r2.round)
        assertEquals(listOf(1L, 3L), r2.order)
        assertEquals(0, r2.position)
        assertEquals(0, r2.knownThisRound)
        assertEquals(4, r2.undoStack.size)
        m.dispatch(SessionEvent.SwipeRight)
        m.dispatch(SessionEvent.SwipeRight)
        assertTrue(m.state.value.finished)
        // No learning cards: only explicit RestartAll starts a new session.
        m.dispatch(SessionEvent.RepeatLearning)
        assertTrue(m.state.value.finished)
        m.dispatch(SessionEvent.RestartAll)
        assertEquals(ids, m.state.value.order)
        assertEquals(1, m.state.value.round)
    }

    @Test
    fun fullUndoChainRestoresStatusesAndCounters() = runTest {
        val statuses = FakeStatuses(mapOf(1L to CardStatus.KNOWN, 2L to CardStatus.NEW, 3L to CardStatus.LEARNING, 4L to CardStatus.NEW))
        val m = machine(statuses)
        m.dispatch(SessionEvent.SwipeLeft) // 1: KNOWN → LEARNING
        m.dispatch(SessionEvent.SwipeRight) // 2: NEW → KNOWN
        m.dispatch(SessionEvent.SwipeRight) // 3: LEARNING → KNOWN
        m.dispatch(SessionEvent.SwipeLeft) // 4: NEW → LEARNING
        assertTrue(m.state.value.finished)
        repeat(4) { m.dispatch(SessionEvent.Undo) }
        val s = m.state.value
        assertEquals(0, s.position)
        assertEquals(0, s.knownThisRound)
        assertEquals(0, s.learningThisRound)
        assertTrue(s.learningIdsThisRound.isEmpty())
        assertFalse(s.canUndo)
        assertEquals(mapOf(1L to CardStatus.KNOWN, 2L to CardStatus.NEW, 3L to CardStatus.LEARNING, 4L to CardStatus.NEW), statuses.map)
        // Лишний Undo ничего не ломает.
        m.dispatch(SessionEvent.Undo)
        assertEquals(0, m.state.value.position)
    }

    @Test
    fun autoplayTicksFlipThenAdvanceWithoutStatusChangesAndGesturePauses() = runTest {
        val statuses = FakeStatuses(ids.associateWith { CardStatus.NEW })
        val m = machine(statuses)
        m.dispatch(SessionEvent.ToggleAutoplay)
        assertTrue(m.state.value.autoplay)
        m.dispatch(SessionEvent.Tick)
        assertTrue(m.state.value.isFlipped)
        m.dispatch(SessionEvent.Tick)
        assertEquals(1, m.state.value.position)
        assertFalse(m.state.value.isFlipped)
        assertTrue(statuses.writes.isEmpty())
        assertEquals(0, m.state.value.knownThisRound + m.state.value.learningThisRound)
        // Жест ставит автопроигрывание на паузу.
        m.dispatch(SessionEvent.Flip)
        assertFalse(m.state.value.autoplay)
        m.dispatch(SessionEvent.Tick)
        assertEquals(1, m.state.value.position) // без автопроигрывания Tick ничего не делает
        // Отмена возвращает пропущенную карточку.
        m.dispatch(SessionEvent.Undo) // снимает Flip? нет — Undo снимает последнюю запись стека (SKIP карточки 1)
        assertEquals(0, m.state.value.position)
        assertTrue(statuses.writes.isEmpty())
        // До конца колоды: автопроигрывание выключается само.
        m.dispatch(SessionEvent.ToggleAutoplay)
        repeat(8) { m.dispatch(SessionEvent.Tick) }
        assertTrue(m.state.value.finished)
        assertFalse(m.state.value.autoplay)
    }

    @Test
    fun restartAllReshufflesWithNewSeedAndResets() = runTest {
        val m = machine(state = SessionState("doc:1:all", DeckBuilder.shuffle(ids, 7L), shuffleSeed = 7L))
        m.dispatch(SessionEvent.SwipeRight)
        m.dispatch(SessionEvent.RestartAll)
        val s = m.state.value
        assertEquals(42L, s.shuffleSeed)
        assertEquals(DeckBuilder.shuffle(ids, 42L), s.order)
        assertEquals(0, s.position)
        assertEquals(1, s.round)
        assertTrue(s.undoStack.isEmpty())
    }

    @Test
    fun stateRoundTripsThroughJson() = runTest {
        val m = machine()
        m.dispatch(SessionEvent.SwipeLeft)
        m.dispatch(SessionEvent.Flip)
        val json = m.state.value.toJson()
        val restored = SessionState.fromJson(json)
        assertEquals(m.state.value, restored)
        assertNull(SessionState.fromJson("garbage"))
        assertEquals(json, persisted.last().let { SessionState.fromJson(it)!!.copy(isFlipped = true).toJson() })
    }

    @Test
    fun undoAcrossRoundBoundaryRestoresPreviousRound() = runTest {
        val m = machine()
        m.dispatch(SessionEvent.SwipeLeft)
        repeat(3) { m.dispatch(SessionEvent.SwipeRight) }
        val beforeLast = m.state.value.copy(position = 3, knownThisRound = 2)
        m.dispatch(SessionEvent.RepeatLearning)
        m.dispatch(SessionEvent.SwipeRight)
        m.dispatch(SessionEvent.Undo)
        m.dispatch(SessionEvent.Undo)
        assertEquals(1, m.state.value.round)
        assertEquals(ids, m.state.value.order)
        assertEquals(beforeLast.position, m.state.value.position)
        assertEquals(beforeLast.knownThisRound, m.state.value.knownThisRound)
    }

    @Test
    fun deckBuilderIsDeterministic() {
        assertEquals(DeckBuilder.shuffle(ids, 1L), DeckBuilder.shuffle(ids, 1L))
        assertEquals(ids, DeckBuilder.order(ids, shuffle = false, seed = 1L))
        assertEquals(ids.toSet(), DeckBuilder.order(ids, shuffle = true, seed = 1L).toSet())
    }
    @Test
    fun legacyJsonRetainsProgressButDiscardsUnprovableUndo() {
        val legacy = """{"deckKey":"all:en:ru","order":[1,2],"position":1,"knownThisRound":1,"undoStack":[{"cardId":1,"previousStatus":"NEW","action":"KNOWN"}]}"""
        val state = SessionState.fromJson(legacy)!!
        assertEquals(1, state.position)
        assertEquals(1, state.knownThisRound)
        assertFalse(state.canUndo)
    }

    @Test
    fun undoPausesAutoplayAndRestartKeepsDatabaseProgress() = runTest {
        val statuses = FakeStatuses(ids.associateWith { CardStatus.NEW })
        val m = machine(statuses)
        m.dispatch(SessionEvent.SwipeRight)
        m.dispatch(SessionEvent.ToggleAutoplay)
        m.dispatch(SessionEvent.Undo)
        assertFalse(m.state.value.autoplay)
        m.dispatch(SessionEvent.SwipeRight)
        m.dispatch(SessionEvent.RestartAll)
        assertFalse(m.state.value.canUndo)
        assertEquals(CardStatus.KNOWN, statuses.map[1L])
    }

    @Test
    fun legacyLearningCountersRemainConsistentAfterNewUndo() = runTest {
        val legacy = """{"deckKey":"doc:1:all","order":[1,2,3,4],"position":1,"learningThisRound":1,"learningIdsThisRound":[1],"undoStack":[{"cardId":1,"previousStatus":"NEW","action":"LEARNING"}]}"""
        val statuses = FakeStatuses(mapOf(1L to CardStatus.LEARNING, 2L to CardStatus.NEW, 3L to CardStatus.NEW, 4L to CardStatus.NEW))
        val restored = SessionState.fromJson(legacy)!!
        val m = machine(statuses, restored)
        m.dispatch(SessionEvent.SwipeLeft)
        m.dispatch(SessionEvent.Undo)
        assertEquals(restored, m.state.value)
        assertEquals(listOf(1L), m.state.value.learningIdsThisRound)
        assertEquals(CardStatus.LEARNING, statuses.map[1L])
    }

}
