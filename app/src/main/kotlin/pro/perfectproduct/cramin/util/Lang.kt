package pro.perfectproduct.cramin.util

import java.util.Locale

/** Языки v1 (SPEC §3): любая пара в любую сторону. */
enum class Lang(val code: String, val label: String) {
    EN("en", "EN"),
    RU("ru", "RU"),
    HE("he", "HE"),
    ;

    val isRtl: Boolean get() = this == HE

    /** Локаль для BreakIterator и TTS; для иврита Android понимает и "iw", и "he". */
    val locale: Locale
        get() = when (this) {
            EN -> Locale.ENGLISH
            RU -> Locale.forLanguageTag("ru")
            HE -> Locale.forLanguageTag("he")
        }

    companion object {
        fun fromCode(code: String?): Lang? = entries.firstOrNull { it.code.equals(code, ignoreCase = true) }
            ?: if (code.equals("iw", ignoreCase = true)) HE else null

        fun requireCode(code: String): Lang = fromCode(code) ?: throw IllegalArgumentException("Unsupported language code: $code")
    }
}
