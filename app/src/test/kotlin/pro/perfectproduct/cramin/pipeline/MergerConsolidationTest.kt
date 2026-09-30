package pro.perfectproduct.cramin.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.perfectproduct.cramin.data.db.Pos
import pro.perfectproduct.cramin.llm.ConsolidateResponse
import pro.perfectproduct.cramin.llm.ConsolidatedItem
import pro.perfectproduct.cramin.llm.ConsolidatedSense
import pro.perfectproduct.cramin.util.Lang

class MergerConsolidationTest {
    private fun vu(idx: Int, lemma: String, g: String, pos: Pos = Pos.NOUN, lv: String? = null, start: Int? = 0) =
        ValidatedUnit(idx, lemma, lemma, lv, pos, g, null, start, start?.plus(lemma.length), null, null)

    @Test
    fun keyNormalizationYoNiqqudCase() {
        assertEquals("ещё|ADV".replace('ё', 'е'), TextNormalizer.lemmaKey("Ещё", "ADV", Lang.RU))
        assertEquals("еще|ADV", TextNormalizer.lemmaKey("ЕЩЁ", "ADV", Lang.RU))
        // Огласованная форма с одним йодом → после снятия огласовок «דיג»; ключ строится по l без огласовок.
        assertEquals("דיג|NOUN", TextNormalizer.lemmaKey("דַּיָּג", "NOUN", Lang.HE))
        assertEquals("דייג|NOUN", TextNormalizer.lemmaKey("דייג", "NOUN", Lang.HE))
        assertEquals("bank|NOUN", TextNormalizer.lemmaKey("  \"Bank\". ", "NOUN", Lang.EN))
        assertEquals("don't|VERB", TextNormalizer.lemmaKey("Don't", "VERB", Lang.EN))
    }

    @Test
    fun mergeGroupsByKeyAndQueuesDivergentTranslations() {
        val units = listOf(
            vu(5, "Bank", "банк"),
            vu(1, "bank", "берег"),
            vu(7, "bank", "Банк"),
            vu(2, "bank", "банк", pos = Pos.VERB),
            vu(3, "library", "библиотека"),
            vu(9, "library", "библиотека"),
        )
        val cards = UnitMerger.merge(units, Lang.EN, Lang.RU)
        assertEquals(listOf("bank|NOUN", "bank|VERB", "library|NOUN"), cards.map { it.lemmaKey })
        val bank = cards[0]
        assertEquals(1, bank.firstSentenceIdx)
        assertEquals("bank", bank.lemma) // самая частая форма среди трёх: bank ×2
        assertTrue(bank.needsConsolidation)
        assertEquals(setOf("берег", "банк"), bank.translations.keys)
        assertEquals("банк", bank.displayTranslation("банк"))
        assertFalse(cards[2].needsConsolidation)
        assertEquals(listOf(cards[0]), Consolidation.batches(cards).single())
    }

    @Test
    fun consolidationAppliesValidResponseAndSortsByOccurrences() {
        val units = listOf(vu(1, "bank", "берег"), vu(2, "bank", "банк"), vu(3, "bank", "берег реки"), vu(4, "bank", "кредитная организация"))
        val card = UnitMerger.merge(units, Lang.EN, Lang.RU).single()
        val batch = Consolidation.buildBatch(listOf(card)) { "sentence $it" }
        assertEquals(listOf(1, 2, 3, 4), batch.items.single().o.map { it.id })
        val response = ConsolidateResponse(listOf(ConsolidatedItem("bank|NOUN", listOf(ConsolidatedSense("банк", listOf(2, 4)), ConsolidatedSense("берег", listOf(1, 3))))))
        val senses = Consolidation.apply(listOf(card), batch, response).getValue("bank|NOUN")
        assertEquals(2, senses.size)
        // Поровну вхождений → раньше тот, чьё вхождение раньше (берег: предложение 1).
        assertEquals(listOf("берег", "банк"), senses.map { it.translation })
        assertEquals(listOf(0, 2), senses[0].unitIndices)
    }

    @Test
    fun consolidationFallbackWhenIdsMissingOrDuplicated() {
        val units = listOf(vu(1, "bank", "берег"), vu(2, "bank", "банк"), vu(3, "bank", "банк"))
        val card = UnitMerger.merge(units, Lang.EN, Lang.RU).single()
        val batch = Consolidation.buildBatch(listOf(card)) { null }
        val dup = ConsolidateResponse(listOf(ConsolidatedItem("bank|NOUN", listOf(ConsolidatedSense("x", listOf(1, 2)), ConsolidatedSense("y", listOf(2, 3))))))
        val missing = ConsolidateResponse(listOf(ConsolidatedItem("bank|NOUN", listOf(ConsolidatedSense("x", listOf(1))))))
        val foreign = ConsolidateResponse(listOf(ConsolidatedItem("bank|NOUN", listOf(ConsolidatedSense("x", listOf(1, 2, 3, 99))))))
        for (bad in listOf(dup, missing, foreign, null)) {
            val senses = Consolidation.apply(listOf(card), batch, bad).getValue("bank|NOUN")
            assertEquals("fallback for $bad", listOf("банк", "берег"), senses.map { it.translation })
            assertEquals(listOf(1, 2), senses[0].unitIndices)
        }
    }

    @Test
    fun examplePrefersMediumLengthThenEarliest() {
        val units = listOf(vu(0, "bank", "банк"), vu(1, "bank", "банк"), vu(2, "bank", "банк"))
        val card = UnitMerger.merge(units, Lang.EN, Lang.RU).single()
        val sense = SenseDraft("банк", listOf(0, 1, 2))
        val short = "Bank."
        val medium = "The bank on the square was closed for the holiday and the fines waited."
        val long = "x".repeat(300)
        assertEquals(1, ExamplePicker.pick(card, sense) { listOf(short, medium, long)[it] })
        assertEquals(0, ExamplePicker.pick(card, sense) { listOf(short, short, long)[it] })
        assertEquals(2, ExamplePicker.pick(card, sense) { listOf(short, long, medium)[it] })
    }
}
