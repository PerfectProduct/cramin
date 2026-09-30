package pro.perfectproduct.cramin.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test
import pro.perfectproduct.cramin.util.Lang

class SurfaceMatcherTest {
    private fun s(idx: Int, text: String) = SentenceDraft(idx, 0, text)

    @Test
    fun wordBoundariesAndCase() {
        val sentences = listOf(s(0, "The Bank was closed; banks and embankments stayed open. BANK!"))
        val m = SurfaceMatcher.findAll(listOf("bank"), sentences, Lang.EN)
        assertEquals(listOf(4, 56), m.map { it.start })
        assertEquals(listOf("Bank", "BANK"), m.map { sentences[0].text.substring(it.start, it.end) })
    }

    @Test
    fun longerSurfaceWinsOverlap() {
        val sentences = listOf(s(0, "The banks closed. One bank stayed."))
        val m = SurfaceMatcher.findAll(listOf("bank", "banks"), sentences, Lang.EN)
        assertEquals(listOf("banks", "bank"), m.map { sentences[0].text.substring(it.start, it.end) })
    }

    @Test
    fun hebrewPrefixesAndNiqqud() {
        val text = "הרשת הייתה גדולה, וברשת היו דגים. רֶשֶׁת חדשה. מרשתות."
        val m = SurfaceMatcher.findAll(listOf("רשת"), listOf(s(0, text)), Lang.HE)
        // הרשת (приставка ה), וברשת (приставки ו+ב), רֶשֶׁת (огласовки); «מרשתות» — не совпадает (суффикс).
        assertEquals(3, m.size)
        assertEquals("רשת", text.substring(m[0].start, m[0].end))
        assertEquals("רֶשֶׁת", text.substring(m[2].start, m[2].end))
    }

    @Test
    fun multiwordAndAcrossSentences() {
        val sentences = listOf(s(0, "The reading room was quiet."), s(1, "A reading-room? No: READING ROOM."))
        val m = SurfaceMatcher.findAll(listOf("reading room"), sentences, Lang.EN)
        assertEquals(listOf(0, 1), m.map { it.sentenceIdx })
        assertEquals(20, m[1].start)
    }
}
