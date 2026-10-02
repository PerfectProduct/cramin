package pro.perfectproduct.cramin.pipeline

import pro.perfectproduct.cramin.util.Lang

/**
 * Поиск фрагмента в тексте без учёта регистра и огласовок с возвратом смещений в **исходной** строке.
 * Для иврита строка «сворачивается» (огласовки убираются), а карта индексов возвращает смещения обратно.
 */
object SpanFinder {

    class Folded(val text: String, val map: IntArray, private val ends: IntArray) {
        /** Both values are UTF-16 offsets; last is the exclusive end, for existing callers. */
        fun original(start: Int, endExclusive: Int): IntRange = map[start]..ends[endExclusive - 1]
    }

    fun fold(s: String, lang: Lang): Folded {
        val text = StringBuilder()
        val starts = ArrayList<Int>()
        val ends = ArrayList<Int>()
        var start = 0
        while (start < s.length) {
            var end = start + Character.charCount(s.codePointAt(start))
            while (end < s.length && Character.getType(s.codePointAt(end)) in setOf(
                    Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt())) {
                end += Character.charCount(s.codePointAt(end))
            }
            val cluster = s.substring(start, end)
            val normalized = java.text.Normalizer.normalize(cluster, java.text.Normalizer.Form.NFC)
            val folded = if (lang == Lang.HE) normalized.filterNot(::isHebrewMark)
                else normalized.lowercase(java.util.Locale.ROOT)
            text.append(folded)
            repeat(folded.length) { starts += start; ends += end }
            start = end
        }
        return Folded(text.toString(), starts.toIntArray(), ends.toIntArray())
    }

    fun foldNeedle(s: String, lang: Lang): String = fold(s, lang).text

    /** First whole surface match; never include neighbouring words in the highlight. */
    fun find(haystack: String, needle: String, lang: Lang): IntRange? =
        SurfaceMatcher.findAll(listOf(needle), listOf(SentenceDraft(0, 0, haystack)), lang)
            .firstOrNull()?.let { it.start..it.end }

    fun isHebrewMark(c: Char): Boolean = c in '֑'..'ֽ' || c == 'ֿ' || c == 'ׁ' || c == 'ׂ' || c == 'ׄ' || c == 'ׅ' || c == 'ׇ'
}
