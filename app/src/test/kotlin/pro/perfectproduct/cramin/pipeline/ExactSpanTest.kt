package pro.perfectproduct.cramin.pipeline

import org.junit.Assert.*
import org.junit.Test
import pro.perfectproduct.cramin.util.Lang

class ExactSpanTest {
    private fun matched(text: String, needle: String, lang: Lang): String? =
        SpanFinder.find(text, needle, lang)?.let { text.substring(it.first, it.last) }

    @Test fun repeatedMentionsKeepDistinctSourceLocationsWithoutGuessingTargetAlignment() {
        val context = object : UnitContext {
            override fun sentence(idx: Int) = "bank and bank"
            override fun segmentTranslation(idx: Int) = "банк и банк"
        }
        val unit = pro.perfectproduct.cramin.llm.ExtractedUnit(0, "bank", "bank", null, "NOUN", "банк", "банк")
        val units = UnitValidator.validate(0..0, listOf(unit, unit), context, emptySet(), Lang.EN, Lang.RU)
        assertEquals(listOf(0, 9), units.map { it.start })
        assertEquals(listOf(4, 13), units.map { it.end })
        assertTrue(units.all { it.targetStart == null })
    }

    @Test fun wordBoundariesAndActualForms() {
        assertNull(matched("riverbank", "bank", Lang.EN))
        assertEquals("берега", matched("У берега реки", "берега", Lang.RU))
        assertEquals("банк", matched("банк семян", "банк", Lang.RU))
        assertEquals("крена", matched("угол крена влево", "крена", Lang.RU))
        assertEquals("железная дорога", matched("Это железная дорога рядом", "железная дорога", Lang.RU))
    }

    @Test fun unicodeOffsetsIncludeMarksAndKeepSurrogatesIntact() {
        assertEquals("שָׁלוֹם", matched("😀 שָׁלוֹם!", "שלום", Lang.HE))
        assertEquals("cafe\u0301", matched("😀 cafe\u0301!", "café", Lang.EN))
        assertNull(matched("𐐀bank", "bank", Lang.EN))
    }
}
