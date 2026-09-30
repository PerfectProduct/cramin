package pro.perfectproduct.cramin.pipeline

import pro.perfectproduct.cramin.llm.TranslatedSegment

/**
 * Валидация ответа перевода секции (SPEC §6.5): сегменты покрывают все id секции подряд, без дыр
 * и пересечений; `to - from ≤ 2`; `t` непустой. Невалидные сегменты отбрасываются, непокрытые
 * предложения возвращаются как дыры для дозапроса.
 */
object TranslationValidator {
    const val MAX_MERGE_SPAN = 2

    data class Result(
        val accepted: List<TranslatedSegment>,
        val missing: List<Int>,
        val rejected: Int,
    ) {
        val isComplete: Boolean get() = missing.isEmpty()
    }

    fun validate(range: IntRange, segments: List<TranslatedSegment>, alreadyCovered: Set<Int> = emptySet()): Result {
        val sorted = segments.sortedWith(compareBy({ it.from }, { it.to }))
        val accepted = ArrayList<TranslatedSegment>()
        val covered = HashSet<Int>(alreadyCovered)
        var rejected = 0
        for (s in sorted) {
            val ok = s.from <= s.to &&
                s.to - s.from <= MAX_MERGE_SPAN &&
                s.t.isNotBlank() &&
                s.from >= range.first && s.to <= range.last &&
                (s.from..s.to).none { it in covered }
            if (!ok) {
                rejected++
                continue
            }
            accepted += s.copy(t = s.t.trim())
            for (i in s.from..s.to) covered += i
        }
        val missing = range.filter { it !in covered }
        return Result(accepted, missing, rejected)
    }

    /** Склейка основного и дозапрошенного ответов в один упорядоченный список. */
    fun merge(primary: List<TranslatedSegment>, fill: List<TranslatedSegment>): List<TranslatedSegment> =
        (primary + fill).sortedBy { it.from }
}
