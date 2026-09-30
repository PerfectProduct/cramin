package pro.perfectproduct.cramin.pipeline

import pro.perfectproduct.cramin.llm.GlossaryEntry
import pro.perfectproduct.cramin.util.Lang

/**
 * Локальный фильтр глоссария (SPEC §6.5): в сообщение попадают термины, встречающиеся в секции
 * (регистронезависимое вхождение `src`), плюс имена собственные — термины, каждое слово которых
 * начинается с заглавной буквы (для иврита признака регистра нет, поэтому только вхождения).
 */
object GlossaryFilter {
    fun filter(glossary: List<GlossaryEntry>, sectionText: String, lang: Lang): List<GlossaryEntry> {
        if (glossary.isEmpty()) return emptyList()
        val haystack = fold(sectionText, lang)
        return glossary.filter { entry ->
            val src = entry.src.trim()
            src.isNotEmpty() && (haystack.contains(fold(src, lang)) || isProperName(src, lang))
        }
    }

    fun isProperName(term: String, lang: Lang): Boolean {
        if (lang == Lang.HE) return false
        val words = term.split(Regex("[\\s-]+")).filter { it.any(Char::isLetter) }
        return words.isNotEmpty() && words.all { w -> w.first { it.isLetter() }.isUpperCase() }
    }

    private fun fold(s: String, lang: Lang): String =
        if (lang == Lang.HE) TextNormalizer.stripHebrewMarks(s) else s.lowercase(lang.locale)
}
