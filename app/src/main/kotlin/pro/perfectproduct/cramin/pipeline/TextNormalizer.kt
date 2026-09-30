package pro.perfectproduct.cramin.pipeline

import pro.perfectproduct.cramin.util.Lang
import java.text.Normalizer

/**
 * Нормализация лемм и переводов для ключей карточек (SPEC §6.7): NFC, нижний регистр,
 * обрезка пунктуации по краям, для ru ё→е, для he удаление огласовок.
 */
object TextNormalizer {

    /** Огласовки и кантилляция иврита: U+0591–U+05BD, U+05BF, U+05C1–U+05C2, U+05C4–U+05C5, U+05C7. */
    private val HEBREW_MARKS = Regex("[\\u0591-\\u05BD\\u05BF\\u05C1\\u05C2\\u05C4\\u05C5\\u05C7]")

    /** Кавычки и пунктуация, которую режем по краям; апострофы внутри слова (don't) остаются. */
    private val EDGE_PUNCT = Regex("^[\\p{P}\\p{S}\\s]+|[\\p{P}\\p{S}\\s]+$")

    fun nfc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)

    fun stripHebrewMarks(s: String): String = HEBREW_MARKS.replace(s, "")

    fun normalize(text: String, lang: Lang): String {
        var s = nfc(text).trim()
        s = EDGE_PUNCT.replace(s, "")
        s = s.replace(Regex("\\s+"), " ")
        s = s.lowercase(lang.locale)
        s = when (lang) {
            Lang.RU -> s.replace('ё', 'е')
            Lang.HE -> stripHebrewMarks(s)
            Lang.EN -> s
        }
        return s
    }

    fun lemmaKey(lemma: String, pos: String, lang: Lang): String = normalize(lemma, lang) + "|" + pos

    /** Ключ дедупликации переводов: те же правила, что для лемм, в языке перевода. */
    fun translationKey(translation: String, targetLang: Lang): String = normalize(translation, targetLang)
}
