package pro.perfectproduct.cramin.pipeline

import pro.perfectproduct.cramin.util.Lang

/**
 * Поиск фрагмента в тексте без учёта регистра и огласовок с возвратом смещений в **исходной** строке.
 * Для иврита строка «сворачивается» (огласовки убираются), а карта индексов возвращает смещения обратно.
 */
object SpanFinder {

    class Folded(val text: String, val map: IntArray) {
        /** Смещение в исходной строке для позиции в свёрнутой; конец диапазона — по последнему символу + 1. */
        fun original(start: Int, endExclusive: Int): IntRange = map[start]..(map[endExclusive - 1] + 1)
    }

    fun fold(s: String, lang: Lang): Folded {
        val sb = StringBuilder(s.length)
        val map = IntArray(s.length)
        var n = 0
        for (i in s.indices) {
            val c = s[i]
            if (lang == Lang.HE && isHebrewMark(c)) continue
            sb.append(if (lang == Lang.HE) c else c.lowercaseChar())
            map[n++] = i
        }
        return Folded(sb.toString(), map.copyOf(n))
    }

    fun foldNeedle(s: String, lang: Lang): String =
        if (lang == Lang.HE) TextNormalizer.stripHebrewMarks(s) else s.lowercase()

    /** Первое вхождение `needle` в `haystack`; смещения относятся к исходной строке. */
    fun find(haystack: String, needle: String, lang: Lang): IntRange? {
        val n = foldNeedle(needle.trim(), lang)
        if (n.isEmpty()) return null
        val h = fold(haystack, lang)
        val pos = h.text.indexOf(n)
        if (pos < 0) return null
        return h.original(pos, pos + n.length)
    }

    fun isHebrewMark(c: Char): Boolean = c in '֑'..'ֽ' || c == 'ֿ' || c == 'ׁ' || c == 'ׂ' || c == 'ׄ' || c == 'ׅ' || c == 'ׇ'
}
