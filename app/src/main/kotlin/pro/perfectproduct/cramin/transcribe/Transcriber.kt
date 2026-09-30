package pro.perfectproduct.cramin.transcribe

import pro.perfectproduct.cramin.util.Lang
import java.io.File

/** Кусок аудио для транскрипции (SPEC §8): файл и его длительность. */
data class AudioPart(val file: File, val durationSeconds: Int)

/** Результат транскрипции одного куска. */
data class Transcript(val text: String, val costUsd: Double?)

/** Нарезка аудио на куски по 10 минут без перекодирования (SPEC §8). */
interface AudioSegmenter {
    suspend fun split(input: File, outDir: File, maxPartSeconds: Int = DEFAULT_PART_SECONDS): List<AudioPart>

    companion object {
        const val DEFAULT_PART_SECONDS = 600
    }
}

/** Транскрипция за интерфейсом (SPEC §5.2): OpenRouter STT в проде, фейк в тестах. */
interface Transcriber {
    suspend fun transcribe(part: AudioPart, lang: Lang?, model: String): Transcript
}
