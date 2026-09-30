package pro.perfectproduct.cramin.pipeline

import pro.perfectproduct.cramin.data.db.Pos
import pro.perfectproduct.cramin.llm.ExtractedUnit
import pro.perfectproduct.cramin.util.Lang

/** Единица после локальной валидации (SPEC §6.6): смещения `f` в предложении и `ft` в сегменте перевода. */
data class ValidatedUnit(
    val sentenceIdx: Int,
    val surface: String,
    val lemma: String,
    val lemmaVocalized: String?,
    val pos: Pos,
    val translation: String,
    val targetSurface: String?,
    val start: Int?,
    val end: Int?,
    val targetStart: Int?,
    val targetEnd: Int?,
) {
    val hasOffsets: Boolean get() = start != null && end != null
}

/** Тексты, нужные валидатору: предложение по idx и сегмент перевода, в который оно входит. */
interface UnitContext {
    fun sentence(idx: Int): String?
    fun segmentTranslation(idx: Int): String?
}

object UnitValidator {

    fun validate(
        chunk: IntRange,
        units: List<ExtractedUnit>,
        context: UnitContext,
        stoplist: Set<String>,
        lang: Lang,
        targetLang: Lang,
    ): List<ValidatedUnit> {
        val out = ArrayList<ValidatedUnit>(units.size)
        for (u in units) {
            // 1. Единицы с i вне чанка отбрасываются.
            if (u.i !in chunk) continue
            val sentence = context.sentence(u.i) ?: continue
            val lemma = TextNormalizer.nfc(u.l).trim()
            val translation = TextNormalizer.nfc(u.g).trim()
            // 5. Пустые l или g.
            if (lemma.isEmpty() || translation.isEmpty()) continue
            val pos = runCatching { Pos.valueOf(u.p.trim().uppercase()) }.getOrNull() ?: continue
            // 4. Стоп-лист: только однословные единицы.
            if (!lemma.contains(' ') && TextNormalizer.normalize(lemma, lang) in stoplist) continue
            // 2. f не найдено — единица остаётся без смещений подсветки.
            val surface = TextNormalizer.nfc(u.f).trim().ifEmpty { lemma }
            val span = SpanFinder.find(sentence, surface, lang)
            // 3. ft не найдено в сегменте — поле обнуляется.
            val ft = u.ft?.let { TextNormalizer.nfc(it).trim() }?.takeIf { it.isNotEmpty() }
            val segment = context.segmentTranslation(u.i)
            val tSpan = if (ft != null && segment != null) SpanFinder.find(segment, ft, targetLang) else null
            out += ValidatedUnit(
                sentenceIdx = u.i,
                surface = surface,
                lemma = lemma,
                lemmaVocalized = if (lang == Lang.HE) u.lv?.trim()?.takeIf { it.isNotEmpty() } else null,
                pos = pos,
                translation = translation,
                targetSurface = if (tSpan != null) ft else null,
                start = span?.first,
                end = span?.last,
                targetStart = tSpan?.first,
                targetEnd = tSpan?.last,
            )
        }
        return out
    }
}
