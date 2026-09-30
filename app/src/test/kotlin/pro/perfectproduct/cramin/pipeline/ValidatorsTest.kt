package pro.perfectproduct.cramin.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.perfectproduct.cramin.data.db.Pos
import pro.perfectproduct.cramin.llm.ExtractedUnit
import pro.perfectproduct.cramin.llm.GlossaryEntry
import pro.perfectproduct.cramin.llm.TranslatedSegment
import pro.perfectproduct.cramin.util.Lang

class ValidatorsTest {

    // --- Перевод (SPEC §6.5) ---------------------------------------------------------

    @Test
    fun translationCoverageHolesOverlapsAndSpan() {
        val r = TranslationValidator.validate(
            10..16,
            listOf(
                TranslatedSegment(10, 10, "a"),
                TranslatedSegment(11, 13, "merged three"),
                TranslatedSegment(13, 14, "overlaps previous"),
                TranslatedSegment(15, 15, "   "),
                TranslatedSegment(16, 19, "too long span"),
            ),
        )
        assertEquals(listOf(10..10, 11..13), r.accepted.map { it.from..it.to })
        assertEquals(listOf(14, 15, 16), r.missing)
        assertEquals(3, r.rejected)
    }

    @Test
    fun holeRefillMergesIntoCompleteSection() {
        val first = TranslationValidator.validate(0..3, listOf(TranslatedSegment(0, 0, "a"), TranslatedSegment(3, 3, "d")))
        assertEquals(listOf(1, 2), first.missing)
        val covered = first.accepted.flatMap { (it.from..it.to).toList() }.toSet()
        val fill = TranslationValidator.validate(0..3, listOf(TranslatedSegment(1, 2, "bc"), TranslatedSegment(0, 0, "dup")), covered)
        assertEquals(1, fill.rejected)
        val merged = TranslationValidator.merge(first.accepted, fill.accepted)
        assertEquals(listOf(0, 1, 3), merged.map { it.from })
        assertTrue(TranslationValidator.validate(0..3, merged).isComplete)
    }

    @Test
    fun unorderedSegmentsAreSortedAndOutOfRangeDropped() {
        val r = TranslationValidator.validate(5..6, listOf(TranslatedSegment(6, 6, "b"), TranslatedSegment(5, 5, "a"), TranslatedSegment(7, 7, "x")))
        assertEquals(listOf(5, 6), r.accepted.map { it.from })
        assertTrue(r.isComplete)
    }

    // --- Глоссарий (SPEC §6.5) ---------------------------------------------------------

    @Test
    fun glossaryFilteredBySectionPlusProperNames() {
        val glossary = listOf(
            GlossaryEntry("reading room", "читальный зал"),
            GlossaryEntry("loan desk", "стойка выдачи"),
            GlossaryEntry("Dr. Alvarez", "доктор Альварес"),
            GlossaryEntry("catalog", "каталог"),
        )
        val out = GlossaryFilter.filter(glossary, "The Reading Room was quiet and the CATALOG was new.", Lang.EN)
        assertEquals(listOf("reading room", "Dr. Alvarez", "catalog"), out.map { it.src })
        assertTrue(GlossaryFilter.isProperName("New York", Lang.EN))
        assertTrue(!GlossaryFilter.isProperName("reading room", Lang.EN))
        assertTrue(!GlossaryFilter.isProperName("ירושלים", Lang.HE))
        val he = GlossaryFilter.filter(listOf(GlossaryEntry("רֶשֶׁת", "сеть"), GlossaryEntry("נמל", "порт")), "הרשת הייתה גדולה", Lang.HE)
        assertEquals(listOf("רֶשֶׁת"), he.map { it.src })
    }

    // --- Единицы (SPEC §6.6) ------------------------------------------------------------

    private val context = object : UnitContext {
        val sentences = mapOf(0 to "The bank was closed on Friday.", 1 to "We sat on the river bank.", 2 to "Nothing here.")
        override fun sentence(idx: Int) = sentences[idx]
        override fun segmentTranslation(idx: Int) = when (idx) { 0, 1 -> "Банк был закрыт в пятницу. Мы сидели на берегу реки." else -> null }
    }
    private val stoplist = TestStoplists.instance.forLang(Lang.EN)

    private fun unit(i: Int, f: String, l: String, g: String, ft: String? = null, p: String = "NOUN") =
        ExtractedUnit(i = i, f = f, l = l, lv = null, p = p, g = g, ft = ft)

    @Test
    fun unitsOutsideChunkStoplistedOrEmptyAreDropped() {
        val out = UnitValidator.validate(
            0..1,
            listOf(
                unit(2, "Nothing", "nothing", "ничего"), // чужой i
                unit(0, "The", "the", "артикль"), // стоп-лист
                unit(0, "bank", "", "банк"), // пустая лемма
                unit(0, "bank", "bank", "  "), // пустой перевод
                unit(0, "bank", "bank", "банк", p = "NOPE"), // невалидная часть речи
                unit(0, "closed", "close", "закрывать", ft = "закрыт", p = "VERB"),
            ),
            context, stoplist, Lang.EN, Lang.RU,
        )
        assertEquals(1, out.size)
        val u = out[0]
        assertEquals("close", u.lemma)
        assertEquals(Pos.VERB, u.pos)
        assertEquals(13, u.start)
        assertEquals(19, u.end)
        assertEquals("закрыт", u.targetSurface)
        assertEquals(9, u.targetStart)
    }

    @Test
    fun surfaceNotFoundKeepsUnitWithoutOffsets_targetNotFoundNullsFt() {
        val out = UnitValidator.validate(
            0..1,
            listOf(unit(1, "riverbank", "bank", "берег", ft = "на побережье")),
            context, stoplist, Lang.EN, Lang.RU,
        )
        assertEquals(1, out.size)
        assertNull(out[0].start)
        assertNull(out[0].targetSurface)
        assertNull(out[0].targetStart)
    }

    @Test
    fun multiwordUnitsBypassStoplistAndMatchCaseInsensitive() {
        val out = UnitValidator.validate(0..1, listOf(unit(1, "SAT ON", "sit on", "сидеть на", p = "PHRASAL_VERB")), context, stoplist, Lang.EN, Lang.RU)
        assertEquals(1, out.size)
        assertEquals(3, out[0].start)
        assertEquals(9, out[0].end)
    }

    @Test
    fun hebrewSpansIgnoreNiqqud() {
        val ctx = object : UnitContext {
            override fun sentence(idx: Int) = "הַדַּיָּג יָצָא לַיָּם"
            override fun segmentTranslation(idx: Int) = "Рыбак вышел в море"
        }
        val out = UnitValidator.validate(0..0, listOf(ExtractedUnit(0, "דַּיָּג", "דייג", "דַּיָּג", "NOUN", "рыбак", "Рыбак")), ctx, emptySet(), Lang.HE, Lang.RU)
        assertEquals(1, out.size)
        // «הַדַּיָּג»: ה + ַ, затем דַּיָּג — смещение 2 в исходной строке, конец после последней буквы.
        assertEquals(2, out[0].start)
        assertEquals("דַּיָּג", ctx.sentence(0).substring(out[0].start!!, out[0].end!!))
        assertEquals("דַּיָּג", out[0].lemmaVocalized)
        assertEquals(0, out[0].targetStart)
    }
}
