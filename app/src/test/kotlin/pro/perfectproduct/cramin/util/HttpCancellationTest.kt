package pro.perfectproduct.cramin.util

import kotlinx.coroutines.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import pro.perfectproduct.cramin.update.ApkDownloader
import pro.perfectproduct.cramin.update.ReleaseInfo
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class HttpCancellationTest {
    @Test fun cancelDelayedHeadersDeletesPartialFile() = checkCancellation(false)
    @Test fun cancelDelayedBodyDeletesPartialFile() = checkCancellation(true)

    private fun checkCancellation(body: Boolean) = runBlocking {
        val root = Files.createTempDirectory("cramin-cancel").toFile()
        val server = MockWebServer()
        server.start()
        try {
            val bytes = "synthetic apk"
            server.enqueue(MockResponse(body = Hashing.sha256Hex(bytes.toByteArray()) + "  sample.apk"))
            val response = MockResponse.Builder().body(bytes)
            if (body) response.bodyDelay(3, TimeUnit.SECONDS) else response.headersDelay(3, TimeUnit.SECONDS)
            server.enqueue(response.build())
            val release = ReleaseInfo("v0.1.99", 99, "0.1.99", "", server.url("/apk").toString(), "sample.apk", bytes.length.toLong(), server.url("/sha").toString())
            val job = launch(Dispatchers.IO) { ApkDownloader(OkHttpClient(), root).download(release) }
            withContext(Dispatchers.IO) {
                assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            }
            delay(100)
            val start = System.nanoTime()
            job.cancelAndJoin()
            val elapsed = (System.nanoTime() - start) / 1_000_000
            assertTrue("cancellation took ${elapsed}ms", elapsed < 1_500)
            assertFalse(root.resolve("updates/sample.apk").exists())
            assertEquals(2, server.requestCount)
        } finally { server.close(); root.deleteRecursively() }
    }
}
