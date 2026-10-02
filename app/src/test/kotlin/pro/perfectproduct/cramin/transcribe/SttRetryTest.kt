package pro.perfectproduct.cramin.transcribe

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import pro.perfectproduct.cramin.util.Lang
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class SttRetryTest {
    @Test fun honorsBothRetryAfterFormatsWithoutLiveStt() = runBlocking {
        val server = MockWebServer()
        val file = Files.createTempFile("synthetic-audio", ".m4a").toFile().apply { writeBytes(byteArrayOf(0, 1, 2)) }
        server.start()
        try {
            server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "9").build())
            server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", ZonedDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(30).format(DateTimeFormatter.RFC_1123_DATE_TIME)).build())
            server.enqueue(MockResponse(body = """{"text":"synthetic transcript","usage":{"cost":0.01}}"""))
            val waits = mutableListOf<Long>()
            val stt = OpenRouterSttTranscriber(OkHttpClient(), { "synthetic-test-key" }, server.url("/").toString().trimEnd('/'), sleeper = { waits += it })
            val result = stt.transcribe(AudioPart(file, 1), Lang.EN, "fake/stt")
            assertEquals("synthetic transcript", result.text)
            assertEquals(9_000L, waits[0])
            assertTrue(waits[1] in 20_000..30_000)
            assertEquals(3, server.requestCount)
        } finally { server.close(); file.delete() }
    }
}
