package pro.perfectproduct.cramin.transcribe

import pro.perfectproduct.cramin.util.useCancellable
import android.util.Base64
import android.util.Base64OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import pro.perfectproduct.cramin.llm.LlmJson
import pro.perfectproduct.cramin.llm.OpenRouterClient
import pro.perfectproduct.cramin.pipeline.ErrorCode
import pro.perfectproduct.cramin.pipeline.PipelineException
import pro.perfectproduct.cramin.util.Lang
import pro.perfectproduct.cramin.util.Log
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * `POST /api/v1/audio/transcriptions` (SPEC §8): `{"model", "input_audio": {"data": <base64>, "format": "m4a"}, "language"}`.
 * Base64 пишется потоково прямо в тело запроса, чтобы не держать в памяти строку в полтора размера файла.
 */
class OpenRouterSttTranscriber(
    http: OkHttpClient,
    private val keyProvider: suspend () -> String?,
    private val baseUrl: String = OpenRouterClient.DEFAULT_BASE_URL,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) : Transcriber {
    private val http = http.newBuilder().readTimeout(10, TimeUnit.MINUTES).writeTimeout(10, TimeUnit.MINUTES).build()

    override suspend fun transcribe(part: AudioPart, lang: Lang?, model: String): Transcript = withContext(Dispatchers.IO) {
        val key = keyProvider()?.takeIf { it.isNotBlank() } ?: throw PipelineException(ErrorCode.NO_KEY, "no key")
        var last: PipelineException? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                return@withContext once(key, part, lang, model)
            } catch (e: PipelineException) {
                if (e.code != ErrorCode.NETWORK && e.code != ErrorCode.SERVER && e.code != ErrorCode.RATE_LIMIT) throw e
                last = e
                Log.w(TAG, "attempt $attempt failed: ${e.code}")
                if (attempt < MAX_ATTEMPTS) sleeper(maxOf(BACKOFF_MS[attempt - 1], e.retryAfterMs ?: 0))
            }
        }
        throw last ?: PipelineException(ErrorCode.TRANSCRIPTION, "exhausted")
    }

    private suspend fun once(key: String, part: AudioPart, lang: Lang?, model: String): Transcript {
        val body = object : RequestBody() {
            override fun contentType() = JSON
            override fun writeTo(sink: BufferedSink) {
                sink.writeUtf8("{\"model\":").writeUtf8(quote(model)).writeUtf8(",\"input_audio\":{\"data\":\"")
                Base64OutputStream(NonClosingSink(sink), Base64.NO_WRAP or Base64.NO_CLOSE).use { b64 ->
                    part.file.inputStream().use { it.copyTo(b64) }
                }
                sink.writeUtf8("\",\"format\":\"m4a\"}")
                if (lang != null) sink.writeUtf8(",\"language\":").writeUtf8(quote(lang.code))
                sink.writeUtf8("}")
            }
        }
        val request = Request.Builder().url("$baseUrl/audio/transcriptions")
            .header("Authorization", "Bearer $key")
            .header("HTTP-Referer", OpenRouterClient.REFERER)
            .header("X-Title", OpenRouterClient.TITLE)
            .post(body)
            .build()
        try {
            http.newCall(request).useCancellable { resp ->
                val text = resp.body.string()
                when {
                    resp.code == 401 || resp.code == 403 -> throw PipelineException(ErrorCode.AUTH, "HTTP ${resp.code}")
                    resp.code == 402 -> throw PipelineException(ErrorCode.PAYMENT, "HTTP 402")
                    resp.code == 429 -> throw PipelineException(ErrorCode.RATE_LIMIT, "HTTP 429", retryAfterMs = pro.perfectproduct.cramin.util.RetryAfter.milliseconds(resp.header("Retry-After")))
                    resp.code >= 500 -> throw PipelineException(ErrorCode.SERVER, "HTTP ${resp.code}", retryAfterMs = pro.perfectproduct.cramin.util.RetryAfter.milliseconds(resp.header("Retry-After")))
                    !resp.isSuccessful -> throw PipelineException(ErrorCode.TRANSCRIPTION, "HTTP ${resp.code}")
                }
                val root = runCatching { LlmJson.lenient.parseToJsonElement(text).jsonObject }.getOrNull()
                    ?: throw PipelineException(ErrorCode.TRANSCRIPTION, "bad response")
                root["error"]?.jsonObject?.let { throw PipelineException(ErrorCode.TRANSCRIPTION, "provider error") }
                val transcript = root["text"]?.jsonPrimitive?.contentOrNull ?: throw PipelineException(ErrorCode.TRANSCRIPTION, "no text")
                val cost = root["usage"]?.jsonObject?.get("cost")?.jsonPrimitive?.doubleOrNull
                Log.i(TAG, "transcribed ${part.durationSeconds}s → ${transcript.length} chars, cost=$cost")
                return Transcript(transcript.trim(), cost)
            }
        } catch (e: IOException) {
            throw PipelineException(ErrorCode.NETWORK, e.javaClass.simpleName, e)
        }
    }

    private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** Base64OutputStream закрывает нижележащий поток; sink OkHttp закрывать нельзя. */
    private class NonClosingSink(private val sink: BufferedSink) : java.io.OutputStream() {
        override fun write(b: Int) { sink.writeByte(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { sink.write(b, off, len) }
        override fun flush() { sink.flush() }
        override fun close() = Unit
    }

    companion object {
        private const val TAG = "Stt"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        const val MAX_ATTEMPTS = 3
        private val BACKOFF_MS = longArrayOf(2000, 5000)
    }
}
