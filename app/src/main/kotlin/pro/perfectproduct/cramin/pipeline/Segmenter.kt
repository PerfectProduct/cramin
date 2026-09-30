package pro.perfectproduct.cramin.pipeline

import pro.perfectproduct.cramin.util.Lang
import java.text.Normalizer

/** Предложение после сегментации: сквозной `idx`, номер абзаца, текст (SPEC §6.2). */
data class SentenceDraft(val idx: Int, val paragraphIdx: Int, val text: String) {
    val words: Int by lazy { WordCounter.count(text) }
}

/**
 * Разбиение текста на предложения по правилам UAX #29. На устройстве — `android.icu.text.BreakIterator`,
 * в JVM-тестах — ICU4J (тот же алгоритм и данные). Возвращает конечные смещения предложений.
 */
fun interface SentenceBreaker {
    fun sentenceEnds(text: String, lang: Lang): List<Int>
}

/**
 * Сегментация (SPEC §6.2): нормализация, абзацы по пустым строкам, предложения через BreakIterator
 * с доклейкой аббревиатур, резка предложений длиннее 600 символов по `;`, `:`, `,`.
 * Для PDF дополнительно склеиваются переносы по дефису и строки внутри абзаца (SPEC §7.3).
 */
class Segmenter(private val breaker: SentenceBreaker) {

    fun segment(raw: String, lang: Lang, pdfMode: Boolean = false): List<SentenceDraft> {
        val out = ArrayList<SentenceDraft>()
        var idx = 0
        paragraphs(raw, pdfMode).forEachIndexed { pIdx, paragraph ->
            // Вне PDF одиночный перевод строки — всегда граница предложения (заголовки, списки).
            val lines = if (pdfMode) listOf(paragraph) else paragraph.split('\n')
            for (line in lines) {
                for (sentence in splitSentences(line.trim(), lang)) {
                    out += SentenceDraft(idx++, pIdx, sentence)
                }
            }
        }
        return out
    }

    /** Нормализация и разбиение на абзацы; публично ради тестов. */
    fun paragraphs(raw: String, pdfMode: Boolean): List<String> {
        var text = Normalizer.normalize(raw, Normalizer.Form.NFC)
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(' ', ' ')
            .replace(' ', '\n')
            .replace(' ', '\n')
            .replace(Regex("[\\t\\u000B\\u000C ]+"), " ")
            .replace(Regex(" ?\\n ?"), "\n")
            .replace(Regex("\\u200B|\\uFEFF"), "")
        if (pdfMode) {
            // «пере-\nнос» → «перенос»: дефис в конце строки перед строчной буквой.
            text = text.replace(Regex("(\\p{L})[-\\u00AD]\\n(\\p{Ll})"), "$1$2")
        }
        val paragraphs = text.split(Regex("\\n\\s*\\n+")).map { it.trim() }.filter { it.isNotEmpty() }
        return if (pdfMode) paragraphs.map { it.replace('\n', ' ').replace(Regex(" {2,}"), " ") } else paragraphs
    }

    fun splitSentences(text: String, lang: Lang): List<String> {
        if (text.isBlank()) return emptyList()
        val ends = breaker.sentenceEnds(text, lang)
        val pieces = ArrayList<String>()
        var start = 0
        for (end in ends) {
            val piece = text.substring(start, end).trim()
            if (piece.isNotEmpty()) pieces += piece
            start = end
        }
        if (start < text.length) text.substring(start).trim().takeIf { it.isNotEmpty() }?.let { pieces += it }
        return mergeAbbreviations(pieces, lang).flatMap { splitLong(it) }
    }

    /** Доклеивает предложение к следующему, если оно кончается аббревиатурой из списка (Dr., т. е.). */
    private fun mergeAbbreviations(pieces: List<String>, lang: Lang): List<String> {
        val abbreviations = Abbreviations.forLang(lang)
        if (abbreviations.isEmpty() || pieces.size < 2) return pieces
        val out = ArrayList<String>()
        val current = StringBuilder()
        for ((i, piece) in pieces.withIndex()) {
            if (current.isNotEmpty()) current.append(' ')
            current.append(piece)
            val isLast = i == pieces.lastIndex
            if (!isLast && endsWithAbbreviation(current, abbreviations)) continue
            out += current.toString()
            current.setLength(0)
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    private fun endsWithAbbreviation(text: CharSequence, abbreviations: Set<String>): Boolean {
        val trimmed = text.trimEnd { it == '"' || it == '\'' || it == ')' || it == '»' || it == '”' || it == ' ' }
        if (!trimmed.endsWith(".")) return false
        val tokens = trimmed.split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false
        val last = tokens.last().lowercase().trimStart('(', '«', '"', '“', '\'')
        if (last in abbreviations) return true
        if (tokens.size >= 2) {
            val lastTwo = (tokens[tokens.size - 2] + " " + tokens.last()).lowercase()
            if (lastTwo in abbreviations) return true
        }
        return false
    }

    /** Предложения длиннее [MAX_SENTENCE_CHARS] режутся по `;`, потом `:`, потом `,`; разделитель остаётся слева. */
    private fun splitLong(sentence: String): List<String> {
        if (sentence.length <= MAX_SENTENCE_CHARS) return listOf(sentence)
        val out = ArrayList<String>()
        var rest = sentence
        while (rest.length > MAX_SENTENCE_CHARS) {
            val cut = findCut(rest)
            if (cut <= 0) break
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }

    private fun findCut(text: String): Int {
        val window = MAX_SENTENCE_CHARS
        for (delimiter in charArrayOf(';', ':', ',')) {
            val pos = text.lastIndexOf(delimiter, window - 1)
            if (pos >= MIN_PIECE_CHARS) return pos + 1
        }
        val space = text.lastIndexOf(' ', window - 1)
        if (space >= MIN_PIECE_CHARS) return space + 1
        return window
    }

    companion object {
        const val MAX_SENTENCE_CHARS = 600

        /** Не оставляем огрызки короче этого при резке длинных предложений. */
        const val MIN_PIECE_CHARS = 40
    }
}

/** Аббревиатуры, после которых точка не завершает предложение. Только те, что редко стоят в конце фразы. */
object Abbreviations {
    private val EN = setOf(
        "mr.", "mrs.", "ms.", "dr.", "prof.", "sr.", "jr.", "st.", "mt.", "gen.", "col.", "lt.", "sgt.", "capt.", "cmdr.",
        "rev.", "hon.", "pres.", "gov.", "sen.", "rep.", "vs.", "e.g.", "i.e.", "cf.", "no.", "fig.", "vol.", "pp.",
        "inc.", "ltd.", "co.", "corp.", "approx.", "dept.", "univ.", "jan.", "feb.", "mar.", "apr.", "jun.", "jul.",
        "aug.", "sep.", "sept.", "oct.", "nov.", "dec.", "u.s.", "u.k.", "ph.d.", "b.a.", "m.a.", "ave.", "blvd.", "rd.",
    )
    private val RU = setOf(
        "т.", "е.", "к.", "д.", "п.", "н.", "г.", "гг.", "ул.", "кв.", "им.", "проф.", "акад.", "др.", "пр.", "см.", "ср.",
        "стр.", "рис.", "табл.", "напр.", "тыс.", "млн.", "млрд.", "руб.", "коп.", "св.", "гл.", "ч.", "ст.", "пп.",
        "т. е.", "т. к.", "т. д.", "т. п.", "т. н.", "и т. д.", "и т. п.", "и др.", "в т. ч.", "т.е.", "т.к.", "т.д.", "т.п.",
        "г-н.", "г-жа.", "экз.", "изд.", "доц.", "мин.", "сек.", "кг.", "км.", "чел.",
    )

    fun forLang(lang: Lang): Set<String> = when (lang) {
        Lang.EN -> EN
        Lang.RU -> RU
        Lang.HE -> emptySet()
    }
}
