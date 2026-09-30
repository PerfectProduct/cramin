package pro.perfectproduct.cramin.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannersTest {
    private fun sentence(idx: Int, para: Int, words: Int) = SentenceDraft(idx, para, (1..words).joinToString(" ") { "w$it" })

    @Test
    fun sectionWordsFormula() {
        // floor(8000 × 0.7 / 2.6) = 2153 < 4000
        assertEquals(2153, SectionPlanner.sectionWords(4000, 8000, 2.6))
        // floor(16000 × 0.7 / 1.4) = 8000 > 4000 → 4000
        assertEquals(4000, SectionPlanner.sectionWords(4000, 16000, 1.4))
        assertEquals(SectionPlanner.MIN_SECTION_WORDS, SectionPlanner.sectionWords(4000, 10, 2.6))
    }

    @Test
    fun sectionsBreakOnlyAtParagraphs() {
        val s = listOf(
            sentence(0, 0, 30), sentence(1, 0, 30),
            sentence(2, 1, 30), sentence(3, 1, 30),
            sentence(4, 2, 30),
        )
        // Лимит 70: абзац 0 (60) помещается, абзац 1 (60) уже нет → новая секция; абзац 2 (30) не влезает к 60.
        assertEquals(listOf(0..1, 2..3, 4..4), SectionPlanner.plan(s, 70))
        // Лимит 200: всё одной секцией одним вызовом.
        assertEquals(listOf(0..4), SectionPlanner.plan(s, 200))
    }

    @Test
    fun oversizedParagraphSplitsBySentences() {
        val s = listOf(sentence(0, 0, 10), sentence(1, 1, 40), sentence(2, 1, 40), sentence(3, 1, 40), sentence(4, 2, 10))
        assertEquals(listOf(0..0, 1..2, 3..3, 4..4), SectionPlanner.plan(s, 90))
    }

    @Test
    fun splitHalfPrefersParagraphBoundary() {
        val s = listOf(sentence(0, 0, 10), sentence(1, 0, 10), sentence(2, 1, 10), sentence(3, 1, 10))
        assertEquals(0..1 to 2..3, SectionPlanner.splitHalf(s, 0..3))
        val single = listOf(sentence(0, 0, 10), sentence(1, 0, 10), sentence(2, 0, 10))
        assertEquals(0..1 to 2..2, SectionPlanner.splitHalf(single, 0..2))
        assertNull(SectionPlanner.splitHalf(listOf(sentence(0, 0, 10)), 0..0))
    }

    @Test
    fun chunksUseWholeSegments() {
        val segs = listOf(SegmentSpan(0, 1, 300), SegmentSpan(2, 2, 300), SegmentSpan(3, 4, 300), SegmentSpan(5, 5, 50))
        // 300 + 300 ≥ 500 → чанк [0..2]; 300 → добавляем 50 → хвост [3..5].
        assertEquals(listOf(0..2, 3..5), ChunkPlanner.plan(segs, 500))
        assertEquals(listOf(0..5), ChunkPlanner.plan(segs, 5000))
        assertTrue(ChunkPlanner.plan(emptyList(), 700).isEmpty())
    }

    @Test
    fun chunkSplitHalfBySegments() {
        val segs = listOf(SegmentSpan(0, 1, 10), SegmentSpan(2, 2, 10), SegmentSpan(3, 4, 10))
        assertEquals(0..1 to 2..4, ChunkPlanner.splitHalf(segs, 0..4))
        assertNull(ChunkPlanner.splitHalf(segs, 3..4))
    }
}
