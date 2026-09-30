package pro.perfectproduct.cramin.pipeline

import pro.perfectproduct.cramin.util.Lang

/**
 * Определение языка по письменности (SPEC §6.1): доля букв каждой письменности среди буквенных
 * символов; побеждает письменность с долей ≥ 60 %. Детерминировано, без словарей.
 */
object LangDetector {
    const val MIN_SHARE = 0.6

    /** Меньше букв — решение ненадёжно: пусть пользователь выберет язык сам. */
    const val MIN_LETTERS = 10

    data class Analysis(val hebrew: Int, val cyrillic: Int, val latin: Int, val letters: Int) {
        val lang: Lang?
            get() {
                if (letters < MIN_LETTERS) return null
                val threshold = letters * MIN_SHARE
                return when {
                    hebrew >= threshold -> Lang.HE
                    cyrillic >= threshold -> Lang.RU
                    latin >= threshold -> Lang.EN
                    else -> null
                }
            }
    }

    fun analyze(text: String): Analysis {
        var hebrew = 0
        var cyrillic = 0
        var latin = 0
        var letters = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (!Character.isLetter(cp)) continue
            letters++
            when (Character.UnicodeScript.of(cp)) {
                Character.UnicodeScript.HEBREW -> hebrew++
                Character.UnicodeScript.CYRILLIC -> cyrillic++
                Character.UnicodeScript.LATIN -> latin++
                else -> Unit
            }
        }
        return Analysis(hebrew, cyrillic, latin, letters)
    }

    fun detect(text: String): Lang? = analyze(text).lang
}
