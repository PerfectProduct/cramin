package pro.perfectproduct.cramin.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BriefInputTest {
    @Test
    fun wholeTextWhenUnderLimit() {
        val p = listOf("one two", "three four five")
        assertEquals("one two\n\nthree four five", BriefInput.select(p, 100))
    }

    @Test
    fun headPlusEvenlySampledParagraphsWithinLimit() {
        val para = (1..1000).joinToString(" ") { "w$it" } // 1000 слов
        val paragraphs = List(40) { "P$it $para" } // 40 × 1001 слов
        val out = BriefInput.select(paragraphs, 25_000)
        val chosen = out.split("\n\n")
        val words = WordCounter.count(out)
        assertTrue("$words", words <= 25_000)
        // Первые ~20 000 слов: абзацы 0..18 подряд, дальше выборка с шагом.
        assertTrue(chosen.take(19).withIndex().all { (i, p) -> p.startsWith("P$i ") })
        assertTrue(chosen.size in 20..25)
        val tailIdx = chosen.drop(19).map { it.substringBefore(' ').drop(1).toInt() }
        assertTrue("равномерность: $tailIdx", tailIdx.zipWithNext().all { (a, b) -> b - a >= 3 })
    }
}
