package pro.perfectproduct.cramin.pipeline

import kotlin.math.floor
import kotlin.math.min

/**
 * Нарезка документа на секции перевода (SPEC §6.5): границы только по абзацам; абзац длиннее
 * секции режется по предложениям. Диапазоны — сквозные индексы предложений, включительно.
 */
object SectionPlanner {
    /** Нижняя граница, чтобы кривой конфиг не породил секции в одно слово. */
    const val MIN_SECTION_WORDS = 50
    const val COMPLETION_SHARE = 0.7

    /** `sectionWords = min(maxSectionWords, floor(maxCompletionTokens × 0.7 / tokensPerWord))`. */
    fun sectionWords(maxSectionWords: Int, maxCompletionTokens: Int, tokensPerWord: Double): Int {
        val byTokens = floor(maxCompletionTokens * COMPLETION_SHARE / tokensPerWord).toInt()
        return min(maxSectionWords, byTokens).coerceAtLeast(MIN_SECTION_WORDS)
    }

    fun plan(sentences: List<SentenceDraft>, sectionWords: Int): List<IntRange> {
        if (sentences.isEmpty()) return emptyList()
        val sections = ArrayList<IntRange>()
        var sectionStart = -1
        var sectionWordsSoFar = 0
        var i = 0
        while (i < sentences.size) {
            // Абзац — максимальная серия предложений с одним paragraphIdx.
            val pIdx = sentences[i].paragraphIdx
            var j = i
            var paraWords = 0
            while (j < sentences.size && sentences[j].paragraphIdx == pIdx) {
                paraWords += sentences[j].words
                j++
            }
            if (paraWords <= sectionWords) {
                if (sectionStart >= 0 && sectionWordsSoFar + paraWords > sectionWords) {
                    sections += sentences[sectionStart].idx..sentences[i - 1].idx
                    sectionStart = -1
                    sectionWordsSoFar = 0
                }
                if (sectionStart < 0) sectionStart = i
                sectionWordsSoFar += paraWords
            } else {
                // Абзац больше секции: закрываем накопленное и режем абзац по предложениям.
                if (sectionStart >= 0) {
                    sections += sentences[sectionStart].idx..sentences[i - 1].idx
                    sectionStart = -1
                    sectionWordsSoFar = 0
                }
                var k = i
                while (k < j) {
                    val start = k
                    var words = 0
                    while (k < j && (k == start || words + sentences[k].words <= sectionWords)) {
                        words += sentences[k].words
                        k++
                    }
                    sections += sentences[start].idx..sentences[k - 1].idx
                }
            }
            i = j
        }
        if (sectionStart >= 0) sections += sentences[sectionStart].idx..sentences.last().idx
        return sections
    }

    /**
     * Деление секции пополам при `finish_reason == "length"` (SPEC §6.5): по абзацам, если их больше
     * одного, иначе по предложениям. null, если в секции одно предложение.
     */
    fun splitHalf(sentences: List<SentenceDraft>, range: IntRange): Pair<IntRange, IntRange>? {
        val inRange = sentences.filter { it.idx in range }
        if (inRange.size < 2) return null
        val paragraphStarts = inRange.indices.filter { it > 0 && inRange[it].paragraphIdx != inRange[it - 1].paragraphIdx }
        val totalWords = inRange.sumOf { it.words }
        val cutAt: Int = if (paragraphStarts.isNotEmpty()) {
            // Ближайшая к середине граница абзаца по словам.
            var best = paragraphStarts.first()
            var bestDiff = Int.MAX_VALUE
            var acc = 0
            for (k in inRange.indices) {
                if (k in paragraphStarts) {
                    val diff = kotlin.math.abs(acc * 2 - totalWords)
                    if (diff < bestDiff) {
                        bestDiff = diff
                        best = k
                    }
                }
                acc += inRange[k].words
            }
            best
        } else {
            var acc = 0
            var cut = 1
            for (k in inRange.indices) {
                acc += inRange[k].words
                if (acc * 2 >= totalWords) {
                    cut = (k + 1).coerceIn(1, inRange.size - 1)
                    break
                }
            }
            cut
        }
        val first = inRange.first().idx..inRange[cutAt - 1].idx
        val second = inRange[cutAt].idx..inRange.last().idx
        return first to second
    }
}

/** Сегмент перевода как единица нарезки чанков извлечения. */
data class SegmentSpan(val firstIdx: Int, val lastIdx: Int, val words: Int)

/**
 * Чанки извлечения (SPEC §6.6): ~`chunkWords` слов оригинала целыми сегментами перевода.
 * Чанк закрывается, как только набрал не меньше `chunkWords`.
 */
object ChunkPlanner {
    fun plan(segments: List<SegmentSpan>, chunkWords: Int): List<IntRange> {
        if (segments.isEmpty()) return emptyList()
        val chunks = ArrayList<IntRange>()
        var start = 0
        var words = 0
        for ((k, seg) in segments.withIndex()) {
            words += seg.words
            if (words >= chunkWords) {
                chunks += segments[start].firstIdx..seg.lastIdx
                start = k + 1
                words = 0
            }
        }
        if (start < segments.size) chunks += segments[start].firstIdx..segments.last().lastIdx
        return chunks
    }

    /** Деление чанка пополам по сегментам при `finish_reason == "length"`; null, если сегмент один. */
    fun splitHalf(segments: List<SegmentSpan>, chunk: IntRange): Pair<IntRange, IntRange>? {
        val inChunk = segments.filter { it.firstIdx >= chunk.first && it.lastIdx <= chunk.last }
        if (inChunk.size < 2) return null
        val cut = inChunk.size / 2
        return (inChunk.first().firstIdx..inChunk[cut - 1].lastIdx) to (inChunk[cut].firstIdx..inChunk.last().lastIdx)
    }
}
