package pro.perfectproduct.cramin.ingest

import kotlinx.serialization.Serializable

/** Observed metadata, never inferred from the title, transcript or target language. */
@Serializable
data class TextProvenance(
    val source: String = "UNKNOWN",
    val trackLanguage: String? = null,
    val trackType: String? = null,
    val sttLanguage: String? = null,
    val sttModel: String? = null,
    val sourceHash: String? = null,
) {
    fun safeSummary(): String = "Text provenance: ${safe(source)}\n" +
        "Track language: ${safe(trackLanguage)}\nTrack type: ${safe(trackType)}\n" +
        "STT request language: ${safe(sttLanguage)}\nSTT model: ${safe(sttModel)}"

    companion object {
        // Do not let corrupt files, URLs, query strings or arbitrary content enter diagnostics.
        fun safe(value: String?): String = value?.takeIf {
            it.length in 1..100 && it.matches(Regex("[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)?"))
        } ?: "UNKNOWN"
    }
}
