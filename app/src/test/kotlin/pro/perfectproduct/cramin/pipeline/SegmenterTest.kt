package pro.perfectproduct.cramin.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.perfectproduct.cramin.testing.Fixtures
import pro.perfectproduct.cramin.util.Lang

class SegmenterTest {
    private val segmenter = Segmenter(Icu4jSentenceBreaker())

    @Test
    fun paragraphsByBlankLinesAndIndices() {
        val text = "First one. Second one!\n\nThird, in paragraph two? Fourth."
        val s = segmenter.segment(text, Lang.EN)
        assertEquals(listOf("First one.", "Second one!", "Third, in paragraph two?", "Fourth."), s.map { it.text })
        assertEquals(listOf(0, 1, 2, 3), s.map { it.idx })
        assertEquals(listOf(0, 0, 1, 1), s.map { it.paragraphIdx })
    }

    @Test
    fun singleNewlineIsSentenceBoundaryOutsidePdf() {
        val s = segmenter.segment("Title without period\nBody sentence one. Body two.", Lang.EN)
        assertEquals(listOf("Title without period", "Body sentence one.", "Body two."), s.map { it.text })
        assertTrue(s.all { it.paragraphIdx == 0 })
    }

    @Test
    fun englishAbbreviationsDoNotSplit() {
        val s = segmenter.splitSentences("Dr. Alvarez opened the room. Mr. Smith came at 5. Then e.g. Ms. Lee left.", Lang.EN)
        assertEquals(listOf("Dr. Alvarez opened the room.", "Mr. Smith came at 5.", "Then e.g. Ms. Lee left."), s)
    }

    @Test
    fun russianAbbreviationsDoNotSplit() {
        val s = segmenter.splitSentences("Старый мастер, т. е. дед Семён, открывал её в семь. Замок 1912 г. Выпуска был немецким. Он пришёл.", Lang.RU)
        assertEquals(3, s.size)
        assertEquals("Старый мастер, т. е. дед Семён, открывал её в семь.", s[0])
        assertEquals("Замок 1912 г. Выпуска был немецким.", s[1])
    }

    @Test
    fun hebrewSentences() {
        val s = segmenter.splitSentences("הדייג יצא לים. הסירה הייתה קטנה! האם הרשת חזקה?", Lang.HE)
        assertEquals(listOf("הדייג יצא לים.", "הסירה הייתה קטנה!", "האם הרשת חזקה?"), s)
    }

    @Test
    fun longSentencesAreCutAtDelimiters() {
        val clause = "the volunteers carried the boxes upstairs one at a time"
        val long = (1..12).joinToString(", ") { clause } + "; then they rested: finally everyone went home and slept."
        assertTrue(long.length > Segmenter.MAX_SENTENCE_CHARS)
        val pieces = segmenter.splitSentences(long, Lang.EN)
        assertTrue(pieces.size >= 2)
        assertTrue(pieces.all { it.length <= Segmenter.MAX_SENTENCE_CHARS })
        assertTrue(pieces.dropLast(1).all { it.endsWith(",") || it.endsWith(";") || it.endsWith(":") })
        assertEquals(long.replace(Regex("\\s+"), " "), pieces.joinToString(" "))
    }

    @Test
    fun pdfModeJoinsHyphenationAndSoftLineBreaks() {
        val pdf = "The volun-\nteers carried the rare\nbooks upstairs.\n\nNext para-\ngraph here."
        val paragraphs = segmenter.paragraphs(pdf, pdfMode = true)
        assertEquals(listOf("The volunteers carried the rare books upstairs.", "Next paragraph here."), paragraphs)
        val s = segmenter.segment(pdf, Lang.EN, pdfMode = true)
        assertEquals(2, s.size)
    }

    @Test
    fun normalizationCollapsesWhitespaceAndNfc() {
        val decomposed = "école  test\r\nline"
        val p = segmenter.paragraphs(decomposed, pdfMode = false)
        assertEquals(listOf("école test\nline"), p)
    }

    @Test
    fun fixturesSegmentIntoReasonableSentences() {
        for (lang in Lang.entries) {
            val s = segmenter.segment(Fixtures.text(lang), lang)
            assertTrue("$lang: ${s.size}", s.size in 15..60)
            assertTrue(s.all { it.text.isNotBlank() && it.text.length <= Segmenter.MAX_SENTENCE_CHARS })
            assertEquals(s.indices.toList(), s.map { it.idx })
            assertTrue(s.last().paragraphIdx >= 5)
        }
    }
}
