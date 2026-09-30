package pro.perfectproduct.cramin.pipeline

import pro.perfectproduct.cramin.util.Lang

/** Найденная поверхностная форма: смещения в исходном тексте предложения. */
data class SurfaceMatch(val sentenceIdx: Int, val start: Int, val end: Int, val surface: String)

/**
 * Поиск всех вхождений известных поверхностных форм (SPEC §6.9): регистронезависимо, по границам слов;
 * для иврита допускаются приставки ו/ה/ב/ל/מ/ש/כ перед формой. LLM не используется.
 */
object SurfaceMatcher {
    private val HEBREW_PREFIXES = setOf('ו', 'ה', 'ב', 'ל', 'מ', 'ש', 'כ')
    private const val MAX_PREFIXES = 3

    fun findAll(surfaces: Collection<String>, sentences: List<SentenceDraft>, lang: Lang): List<SurfaceMatch> {
        val needles = surfaces.map { it to SpanFinder.foldNeedle(it.trim(), lang) }
            .filter { it.second.isNotEmpty() }
            .sortedByDescending { it.second.length }
        if (needles.isEmpty()) return emptyList()
        val out = ArrayList<SurfaceMatch>()
        for (s in sentences) {
            val folded = SpanFinder.fold(s.text, lang)
            val taken = ArrayList<IntRange>()
            for ((original, needle) in needles) {
                var from = 0
                while (true) {
                    val pos = folded.text.indexOf(needle, from)
                    if (pos < 0) break
                    from = pos + 1
                    val endEx = pos + needle.length
                    if (!isBoundaryBefore(folded.text, pos, lang) || !isBoundaryAfter(folded.text, endEx)) continue
                    val range = folded.original(pos, endEx)
                    // Более длинная форма уже заняла это место (например, «banks» поверх «bank»).
                    if (taken.any { it.first < range.last && range.first < it.last }) continue
                    taken += range
                    out += SurfaceMatch(s.idx, range.first, range.last, original)
                }
            }
        }
        return out.sortedWith(compareBy({ it.sentenceIdx }, { it.start }))
    }

    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    private fun isBoundaryAfter(text: String, endEx: Int): Boolean = endEx >= text.length || !isWordChar(text[endEx])

    private fun isBoundaryBefore(text: String, pos: Int, lang: Lang): Boolean {
        if (pos == 0) return true
        if (!isWordChar(text[pos - 1])) return true
        if (lang != Lang.HE) return false
        // Иврит: слово может начинаться с одной–трёх приставок перед формой.
        var i = pos - 1
        var n = 0
        while (i >= 0 && isWordChar(text[i])) {
            if (text[i] !in HEBREW_PREFIXES || ++n > MAX_PREFIXES) return false
            i--
        }
        return true
    }
}
