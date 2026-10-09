package pro.perfectproduct.cramin.pipeline

import pro.perfectproduct.cramin.llm.LlmException
import pro.perfectproduct.cramin.llm.TranslatedSegment
import pro.perfectproduct.cramin.util.Hashing

/** Mechanical evidence only. Correct echoes/anchors cannot prove semantic equivalence. */
object TranslationIntegrity {
    const val VERSION = 1
    enum class Evidence { MECHANICALLY_VERIFIED, UNKNOWN }

    fun sourceId(sentence: SentenceDraft): String = "s${sentence.idx}:" + Hashing.sha256Hex(sentence.text.toByteArray(Charsets.UTF_8))

    // Alternatives prevent numbers inside code/URLs from becoming duplicate anchors.
    private val anchor = Regex("""`[^`\r\n]+`|(?i:https?://)[^\s<>"`]+|(?<![\p{L}\p{N}_])\d+(?:[.,]\d+)*(?![\p{L}\p{N}_]|[.,]\d)""")
    private val heading = Regex("""#{1,6}\s+(\d+)[.)]""")
    fun anchors(text: String): List<String> = anchor.findAll(text).map {
        if (it.value.startsWith("http", ignoreCase = true)) it.value.trimEnd('.', ',', ';', ')', ']', '}') else it.value
    }.toList()

    /** A partial answer may have holes, but every returned segment must be trustworthy mechanically. */
    fun check(sentences: List<SentenceDraft>, segments: List<TranslatedSegment>, requireProof: Boolean): Evidence {
        val byId = sentences.associateBy { it.idx }
        var previousEnd: Int? = null
        var proved = segments.isNotEmpty()
        for (segment in segments) {
            if (segment.to.toLong() - segment.from.toLong() !in 0L..TranslationValidator.MAX_MERGE_SPAN.toLong() || segment.t.isBlank())
                invalid("segment shape")
            if (previousEnd != null && segment.from <= previousEnd) invalid("segment order or overlap")
            previousEnd = segment.to
            val source = (segment.from..segment.to).map { byId[it] ?: invalid("foreign sentence id") }
            val ids = segment.sourceIds
            if (ids != null) {
                if (ids != source.map(::sourceId)) invalid("source identity")
            } else {
                if (requireProof) invalid("source identity missing")
                proved = false
            }
            val text = source.joinToString(" ") { it.text }
            if (ids != null) {
                if (anchors(text) != anchors(segment.t)) invalid("immutable anchors")
            } else {
                // Old protocol did not require verbatim numbers/formatting. Missing evidence is UNKNOWN.
                // An explicit foreign Markdown heading is still detectable without imposing the new contract.
                val translatedHeadings = heading.findAll(segment.t).map { it.groupValues[1] }.toList()
                if (translatedHeadings.isNotEmpty() && translatedHeadings != heading.findAll(text).map { it.groupValues[1] }.toList())
                    invalid("foreign legacy heading")
            }
        }
        return if (proved) Evidence.MECHANICALLY_VERIFIED else Evidence.UNKNOWN
    }

    private fun invalid(reason: String): Nothing = throw LlmException.InvalidResponse("translation integrity: $reason")
}
