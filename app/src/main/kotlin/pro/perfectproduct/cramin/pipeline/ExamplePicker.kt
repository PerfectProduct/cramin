package pro.perfectproduct.cramin.pipeline

/** Пример смысла (SPEC §6.9): предпочтение предложениям длиной 40–220 символов, затем самому раннему. */
object ExamplePicker {
    const val MIN_GOOD = 40
    const val MAX_GOOD = 220

    /** Возвращает индекс единицы (в `card.units`), чьё предложение станет примером. */
    fun pick(card: MergedCard, sense: SenseDraft, sentence: (Int) -> String?): Int {
        val candidates = sense.unitIndices.sortedWith(compareBy({ card.units[it].sentenceIdx }, { card.units[it].start ?: Int.MAX_VALUE }))
        val good = candidates.firstOrNull { i ->
            val len = sentence(card.units[i].sentenceIdx)?.length ?: 0
            len in MIN_GOOD..MAX_GOOD
        }
        return good ?: candidates.first()
    }
}
