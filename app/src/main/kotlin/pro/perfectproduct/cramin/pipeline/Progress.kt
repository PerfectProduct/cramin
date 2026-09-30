package pro.perfectproduct.cramin.pipeline

import pro.perfectproduct.cramin.data.db.DocStatus

/**
 * Веса стадий (SPEC §6.11): получение 0–5 %, транскрипция 5–30 % (если есть), бриф 5 %, перевод 45 %,
 * извлечение 35 %, консолидация 10 %. Без транскрипции остальные веса нормируются на 95 %.
 */
class Progress(private val hasTranscription: Boolean) {
    private val fetch = 0.05f
    private val transcribe = if (hasTranscription) 0.25f else 0f
    private val scale = (1f - fetch - transcribe) / (0.05f + 0.45f + 0.35f + 0.10f)
    private val brief = 0.05f * scale
    private val translate = 0.45f * scale
    private val extract = 0.35f * scale
    private val consolidate = 0.10f * scale

    fun start(status: DocStatus): Float = when (status) {
        DocStatus.QUEUED, DocStatus.FETCHING -> 0f
        DocStatus.TRANSCRIBING -> fetch
        DocStatus.BRIEFING -> fetch + transcribe
        DocStatus.TRANSLATING -> fetch + transcribe + brief
        DocStatus.EXTRACTING -> fetch + transcribe + brief + translate
        DocStatus.CONSOLIDATING -> fetch + transcribe + brief + translate + extract
        DocStatus.READY -> 1f
        DocStatus.FAILED -> 0f
    }

    /** Прогресс внутри стадии: `fraction` — доля выполненных единиц стадии. */
    fun within(status: DocStatus, fraction: Float): Float {
        val weight = when (status) {
            DocStatus.FETCHING -> fetch
            DocStatus.TRANSCRIBING -> transcribe
            DocStatus.BRIEFING -> brief
            DocStatus.TRANSLATING -> translate
            DocStatus.EXTRACTING -> extract
            DocStatus.CONSOLIDATING -> consolidate
            else -> 0f
        }
        return (start(status) + weight * fraction.coerceIn(0f, 1f)).coerceIn(0f, 1f)
    }
}
