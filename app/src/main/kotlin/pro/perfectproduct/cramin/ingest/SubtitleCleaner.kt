package pro.perfectproduct.cramin.ingest

import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/** Реплика субтитров: время в миллисекундах и текст без тегов. */
data class Cue(val startMs: Long, val endMs: Long, val text: String)

/**
 * Очистка субтитров (SPEC §7.2): VTT/TTML/SRT → реплики без таймкодов и тегов, дубли убираются,
 * абзацы формируются по паузам больше 2 секунд.
 */
object SubtitleCleaner {
    const val PARAGRAPH_GAP_MS = 2000L

    enum class Format { VTT, TTML, SRT }

    fun detect(content: String, hint: String? = null): Format {
        val h = hint?.lowercase().orEmpty()
        return when {
            h.contains("ttml") || h.contains("xml") || content.trimStart().startsWith("<") -> Format.TTML
            h.contains("vtt") || content.trimStart().startsWith("WEBVTT") -> Format.VTT
            else -> Format.SRT
        }
    }

    fun toText(content: String, format: Format = detect(content)): String = paragraphs(parse(content, format)).joinToString("\n\n")

    fun parse(content: String, format: Format): List<Cue> = when (format) {
        Format.VTT -> parseVtt(content)
        Format.SRT -> parseSrt(content)
        Format.TTML -> parseTtml(content)
    }

    /** Абзацы по паузам; соседние одинаковые реплики (прокрутка) схлопываются. */
    fun paragraphs(cues: List<Cue>): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var lastEnd = -1L
        var lastText: String? = null
        for (cue in cues.sortedBy { it.startMs }) {
            val text = cue.text.trim()
            if (text.isEmpty()) continue
            if (text == lastText) {
                lastEnd = maxOf(lastEnd, cue.endMs)
                continue
            }
            if (lastEnd >= 0 && cue.startMs - lastEnd > PARAGRAPH_GAP_MS && current.isNotEmpty()) {
                out += current.toString()
                current.setLength(0)
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(text)
            lastEnd = maxOf(lastEnd, cue.endMs)
            lastText = text
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    // --- VTT / SRT ---------------------------------------------------------------------

    private val TIMING = Regex("(\\d{1,2}:)?(\\d{1,2}):(\\d{2})[.,](\\d{1,3})\\s*-->\\s*(\\d{1,2}:)?(\\d{1,2}):(\\d{2})[.,](\\d{1,3})")

    private fun parseVtt(content: String): List<Cue> = parseBlocks(content.replace("\r\n", "\n").replace('\r', '\n'), skipHeader = true)

    private fun parseSrt(content: String): List<Cue> = parseBlocks(content.replace("\r\n", "\n").replace('\r', '\n'), skipHeader = false)

    private fun parseBlocks(text: String, skipHeader: Boolean): List<Cue> {
        val cues = ArrayList<Cue>()
        val lines = text.lines()
        var i = 0
        if (skipHeader) {
            // Заголовок WEBVTT и блоки NOTE/STYLE/REGION до первой пустой строки.
            while (i < lines.size && lines[i].isNotBlank()) i++
        }
        while (i < lines.size) {
            val line = lines[i]
            val m = TIMING.find(line)
            if (m == null) {
                i++
                continue
            }
            val start = ms(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4])
            val end = ms(m.groupValues[5], m.groupValues[6], m.groupValues[7], m.groupValues[8])
            i++
            val sb = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank()) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(stripTags(lines[i]))
                i++
            }
            val t = sb.toString().trim()
            if (t.isNotEmpty()) cues += Cue(start, end, t)
        }
        return cues
    }

    private fun ms(h: String, m: String, s: String, frac: String): Long {
        val hours = h.trimEnd(':').toLongOrNull() ?: 0L
        val millis = frac.padEnd(3, '0').take(3).toLong()
        return ((hours * 60 + m.toLong()) * 60 + s.toLong()) * 1000 + millis
    }

    /** Теги `<c>`, `<v Name>`, `<i>`, временные метки `<00:00:01.000>` и HTML-сущности. */
    fun stripTags(line: String): String {
        val noTags = line.replace(Regex("<[^>]*>"), "")
        return Parser.unescapeEntities(noTags, false).replace(Regex("\\s+"), " ").trim()
    }

    // --- TTML ----------------------------------------------------------------------------

    private fun parseTtml(content: String): List<Cue> {
        val doc = Jsoup.parse(content, "", Parser.xmlParser())
        val cues = ArrayList<Cue>()
        for (p in doc.select("p")) {
            val begin = ttmlTime(p.attr("begin")) ?: continue
            val end = ttmlTime(p.attr("end")) ?: (begin + (ttmlTime(p.attr("dur")) ?: 0L))
            // <br/> — перенос строки внутри реплики; вложенные <span> дают текст как есть.
            val html = p.html().replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), " ")
            val text = Parser.unescapeEntities(html.replace(Regex("<[^>]*>"), ""), false).replace(Regex("\\s+"), " ").trim()
            if (text.isNotEmpty()) cues += Cue(begin, end, text)
        }
        return cues
    }

    /** `hh:mm:ss.mmm`, `mm:ss.mmm`, `12.5s`, `1500ms`. */
    fun ttmlTime(raw: String): Long? {
        val v = raw.trim()
        if (v.isEmpty()) return null
        if (v.endsWith("ms")) return v.removeSuffix("ms").toDoubleOrNull()?.toLong()
        if (v.endsWith("s")) return v.removeSuffix("s").toDoubleOrNull()?.let { (it * 1000).toLong() }
        val parts = v.split(':')
        if (parts.size !in 2..3) return null
        val sec = parts.last().replace(',', '.').toDoubleOrNull() ?: return null
        val min = parts[parts.size - 2].toLongOrNull() ?: return null
        val hour = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0L
        return ((hour * 60 + min) * 60 * 1000) + (sec * 1000).toLong()
    }
}
