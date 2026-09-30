package pro.perfectproduct.cramin.transcribe

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pro.perfectproduct.cramin.pipeline.ErrorCode
import pro.perfectproduct.cramin.pipeline.PipelineException
import pro.perfectproduct.cramin.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Нарезка M4A на куски по 10 минут через MediaExtractor + MediaMuxer без перекодирования (SPEC §8):
 * сэмплы AAC копируются как есть, границы — по временным меткам.
 */
class MediaAudioSegmenter : AudioSegmenter {

    override suspend fun split(input: File, outDir: File, maxPartSeconds: Int): List<AudioPart> = withContext(Dispatchers.IO) {
        outDir.mkdirs()
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw PipelineException(ErrorCode.TRANSCRIPTION, "no audio track")
            val format = extractor.getTrackFormat(trackIndex)
            val totalUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            extractor.selectTrack(trackIndex)
            val partUs = maxPartSeconds * 1_000_000L
            val buffer = ByteBuffer.allocate(1 shl 20)
            val info = MediaCodec.BufferInfo()
            val parts = ArrayList<AudioPart>()
            var partIndex = 0
            var muxer: MediaMuxer? = null
            var muxerTrack = -1
            var partStartUs = 0L
            var lastUs = 0L
            fun closePart() {
                muxer?.let {
                    it.stop()
                    it.release()
                    val file = File(outDir, "part-%03d.m4a".format(partIndex))
                    parts += AudioPart(file, ((lastUs - partStartUs) / 1_000_000L).toInt().coerceAtLeast(1))
                    partIndex++
                }
                muxer = null
            }
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val timeUs = extractor.sampleTime
                if (muxer == null || timeUs - partStartUs >= partUs) {
                    closePart()
                    val file = File(outDir, "part-%03d.m4a".format(partIndex))
                    muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { m ->
                        muxerTrack = m.addTrack(format)
                        m.start()
                    }
                    partStartUs = timeUs
                }
                info.offset = 0
                info.size = size
                info.presentationTimeUs = timeUs - partStartUs
                info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer?.writeSampleData(muxerTrack, buffer, info)
                lastUs = timeUs
                extractor.advance()
            }
            lastUs = maxOf(lastUs, if (totalUs > 0 && parts.isEmpty() && muxer != null) totalUs else lastUs)
            closePart()
            Log.i(TAG, "split ${input.length()} bytes into ${parts.size} parts (${totalUs / 1_000_000}s)")
            parts
        } catch (e: PipelineException) {
            throw e
        } catch (e: Exception) {
            throw PipelineException(ErrorCode.TRANSCRIPTION, "audio split failed: ${e.javaClass.simpleName}", e)
        } finally {
            extractor.release()
        }
    }

    companion object {
        private const val TAG = "AudioSegmenter"
    }
}
