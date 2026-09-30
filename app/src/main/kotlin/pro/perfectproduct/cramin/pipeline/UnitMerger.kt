package pro.perfectproduct.cramin.pipeline

import pro.perfectproduct.cramin.data.db.Pos
import pro.perfectproduct.cramin.util.Lang

/**
 * Карточка после слияния единиц (SPEC §6.7): все единицы документа с одним `lemmaKey`.
 * `translations` — нормализованный перевод → индексы единиц в [units] (порядок первого появления).
 */
data class MergedCard(
    val lemmaKey: String,
    val lemma: String,
    val lemmaVocalized: String?,
    val pos: Pos,
    val units: List<ValidatedUnit>,
    val translations: Map<String, List<Int>>,
) {
    val firstSentenceIdx: Int get() = units.minOf { it.sentenceIdx }

    /** Два и больше различных переводов — в очередь консолидации (SPEC §6.7). */
    val needsConsolidation: Boolean get() = translations.size >= 2

    /** Исходная форма перевода для нормализованного ключа: самая частая среди единиц. */
    fun displayTranslation(key: String): String {
        val idx = translations[key].orEmpty()
        return idx.map { units[it].translation }.mostFrequent()
    }
}

object UnitMerger {
    fun merge(units: List<ValidatedUnit>, lang: Lang, targetLang: Lang): List<MergedCard> {
        val groups = LinkedHashMap<String, MutableList<ValidatedUnit>>()
        for (u in units.sortedWith(compareBy({ it.sentenceIdx }, { it.start ?: Int.MAX_VALUE }))) {
            val key = TextNormalizer.lemmaKey(u.lemma, u.pos.name, lang)
            groups.getOrPut(key) { mutableListOf() }.add(u)
        }
        return groups.map { (key, list) ->
            val translations = LinkedHashMap<String, MutableList<Int>>()
            list.forEachIndexed { i, u ->
                translations.getOrPut(TextNormalizer.translationKey(u.translation, targetLang)) { mutableListOf() }.add(i)
            }
            MergedCard(
                lemmaKey = key,
                lemma = list.map { it.lemma }.mostFrequent(),
                lemmaVocalized = if (lang == Lang.HE) list.firstNotNullOfOrNull { it.lemmaVocalized } else null,
                pos = list.first().pos,
                units = list,
                translations = translations,
            )
        }.sortedBy { it.firstSentenceIdx }
    }
}

/** Самый частый элемент; при равенстве — первый встреченный. */
internal fun List<String>.mostFrequent(): String {
    val counts = LinkedHashMap<String, Int>()
    for (s in this) counts[s] = (counts[s] ?: 0) + 1
    return counts.maxByOrNull { it.value }?.key ?: first()
}
