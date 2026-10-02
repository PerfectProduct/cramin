package pro.perfectproduct.cramin.pipeline

import java.text.Normalizer
import java.util.Locale

/** Conservative identity: no stemming, synonym guesses or removal of meaning-bearing diacritics. */
object MeaningKey {
    fun of(translation: String): String = Normalizer.normalize(translation, Normalizer.Form.NFC)
        .trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
}
