package pro.perfectproduct.cramin.study

import pro.perfectproduct.cramin.data.db.Direction
import pro.perfectproduct.cramin.data.repo.StudyCard
import pro.perfectproduct.cramin.pipeline.SentenceDraft
import pro.perfectproduct.cramin.pipeline.SurfaceMatcher
import pro.perfectproduct.cramin.util.Lang

/** UTF-16 offsets, end exclusive. A span is accepted only at its saved location; never repaired by guessing. */
data class StudySpan(val start: Int, val end: Int)

data class StudyCardContent(
    val showSource: Boolean,
    val word: String,
    val vocalized: String?,
    val context: String?,
    val span: StudySpan?,
    val speech: String,
    val language: Lang,
)

fun StudyCard.content(direction: Direction, isFront: Boolean): StudyCardContent {
    val source = (direction == Direction.SRC_FRONT) == isFront
    val example = visibleSenses.firstOrNull()?.example
    val text = (if (source) example?.sentence else example?.translation)?.takeIf { it.isNotBlank() }
    val language = if (source) lang else targetLang
    val word = if (source) lemma else visibleSenses.joinToString("; ") { it.translation }
    return StudyCardContent(
        showSource = source,
        word = word,
        vocalized = if (source) lemmaVocalized else null,
        context = text,
        span = text?.let {
            savedStudySpan(it, if (source) example?.start else example?.targetStart,
                if (source) example?.end else example?.targetEnd,
                if (source) example?.surface else example?.targetSurface, language,
                requireUnique = !source)
        },
        speech = if (source) lemmaVocalized ?: lemma else word,
        language = language,
    )
}

/** Same normalization/word boundaries as ingestion, including Hebrew marks and UTF-16 mapping.
 * Source repetitions have saved occurrence positions. Target repetitions have no reliable alignment.
 */
fun savedStudySpan(text: String, start: Int?, end: Int?, surface: String?, lang: Lang, requireUnique: Boolean): StudySpan? {
    if (start == null || end == null || start < 0 || end > text.length || start >= end || surface.isNullOrBlank()) return null
    val characters = java.text.BreakIterator.getCharacterInstance(java.util.Locale.ROOT).apply { setText(text) }
    if (!characters.isBoundary(start) || !characters.isBoundary(end)) return null
    val matches = SurfaceMatcher.findAll(listOf(surface), listOf(SentenceDraft(0, 0, text)), lang)
    if (requireUnique && matches.size != 1) return null
    return matches.singleOrNull { it.start == start && it.end == end }?.let { StudySpan(it.start, it.end) }
}
