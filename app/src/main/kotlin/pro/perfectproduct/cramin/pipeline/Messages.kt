package pro.perfectproduct.cramin.pipeline

import kotlinx.serialization.json.Json
import pro.perfectproduct.cramin.llm.Brief
import pro.perfectproduct.cramin.llm.ConsolidateItemInput
import pro.perfectproduct.cramin.llm.GlossaryEntry
import pro.perfectproduct.cramin.util.Lang

/** Строка контекста продолжения (SPEC §6.5): уже переведённый сегмент предыдущей секции. */
data class ContextLine(val from: Int, val to: Int, val source: String, val translation: String)

/** Пара «предложения — сегмент перевода» для извлечения (SPEC §6.6). */
data class SegmentPair(val sentences: List<SentenceDraft>, val translation: String)

/**
 * Сообщения пользователя для стадий. Формат — из SPEC §6.4–§6.8; системные промпты — в [pro.perfectproduct.cramin.llm.Prompts].
 * Формат разбирает и FakeLlmClient, поэтому он зафиксирован тестами.
 */
object Messages {
    private val json = Json { encodeDefaults = true }

    fun brief(lang: Lang, targetLang: Lang, text: String): String = buildString {
        append("SOURCE: ").append(lang.code).append('\n')
        append("TARGET: ").append(targetLang.code).append('\n')
        append("TEXT:\n").append(text)
    }

    fun translate(
        lang: Lang,
        targetLang: Lang,
        brief: Brief?,
        glossary: List<GlossaryEntry>,
        context: List<ContextLine>,
        sentences: List<SentenceDraft>,
    ): String = buildString {
        append("SOURCE: ").append(lang.code).append('\n')
        append("TARGET: ").append(targetLang.code).append('\n')
        if (brief != null) {
            append("BRIEF: ").append(oneLine(brief.summary)).append(" | domain: ").append(oneLine(brief.domain))
                .append(" | register: ").append(brief.register).append('\n')
        } else {
            append("BRIEF: (not available)\n")
        }
        appendGlossary(glossary)
        if (context.isNotEmpty()) {
            append("CONTEXT (already translated):\n")
            for (c in context) {
                append(idLabel(c.from, c.to)).append(' ').append(oneLine(c.source)).append(" => ").append(oneLine(c.translation)).append('\n')
            }
        }
        append("SENTENCES:\n")
        for (s in sentences) append('[').append(s.idx).append("] ").append(oneLine(s.text)).append('\n')
    }.trimEnd()

    fun extract(lang: Lang, targetLang: Lang, glossary: List<GlossaryEntry>, pairs: List<SegmentPair>): String = buildString {
        append("SOURCE: ").append(lang.code).append('\n')
        append("TARGET: ").append(targetLang.code).append('\n')
        appendGlossary(glossary)
        append("PAIRS:\n")
        for (p in pairs) {
            for (s in p.sentences) append('[').append(s.idx).append("] ").append(oneLine(s.text)).append('\n')
            append("=> ").append(idLabel(p.sentences.first().idx, p.sentences.last().idx)).append(' ').append(oneLine(p.translation)).append('\n')
        }
    }.trimEnd()

    fun consolidate(lang: Lang, targetLang: Lang, items: List<ConsolidateItemInput>): String = buildString {
        append("SOURCE: ").append(lang.code).append('\n')
        append("TARGET: ").append(targetLang.code).append('\n')
        append("ITEMS:\n").append(json.encodeToString(items))
    }

    private fun StringBuilder.appendGlossary(glossary: List<GlossaryEntry>) {
        append("GLOSSARY:\n")
        if (glossary.isEmpty()) {
            append("(none)\n")
            return
        }
        for (g in glossary) {
            append("- ").append(oneLine(g.src)).append(" → ").append(oneLine(g.tgt))
            g.note?.takeIf { it.isNotBlank() }?.let { append(" (note: ").append(oneLine(it)).append(')') }
            append('\n')
        }
    }

    fun idLabel(from: Int, to: Int): String = if (from == to) "[$from]" else "[$from-$to]"

    private fun oneLine(s: String): String = s.replace(Regex("\\s*\\n\\s*"), " ").trim()

    // --- Разбор своих же сообщений (нужен фейку и тестам) ------------------------------

    private val SENTENCE_LINE = Regex("^\\[(\\d+)] (.*)$")
    private val SEGMENT_LINE = Regex("^=> \\[(\\d+)(?:-(\\d+))?] (.*)$")

    fun parseSentences(message: String, section: String = "SENTENCES:"): List<SentenceDraft> {
        val lines = message.lines()
        val start = lines.indexOf(section)
        if (start < 0) return emptyList()
        return lines.drop(start + 1).mapNotNull { line ->
            SENTENCE_LINE.matchEntire(line)?.let { m -> SentenceDraft(m.groupValues[1].toInt(), 0, m.groupValues[2]) }
        }
    }

    fun parsePairs(message: String): List<SegmentPair> {
        val lines = message.lines()
        val start = lines.indexOf("PAIRS:")
        if (start < 0) return emptyList()
        val pairs = ArrayList<SegmentPair>()
        var current = ArrayList<SentenceDraft>()
        for (line in lines.drop(start + 1)) {
            SENTENCE_LINE.matchEntire(line)?.let { m ->
                current += SentenceDraft(m.groupValues[1].toInt(), 0, m.groupValues[2])
                return@let
            } ?: SEGMENT_LINE.matchEntire(line)?.let { m ->
                pairs += SegmentPair(current, m.groupValues[3])
                current = ArrayList()
            }
        }
        return pairs
    }

    fun parseHeader(message: String, key: String): String? =
        message.lineSequence().firstOrNull { it.startsWith("$key: ") }?.removePrefix("$key: ")?.trim()
}
