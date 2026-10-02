package pro.perfectproduct.cramin.update

import pro.perfectproduct.cramin.util.useCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import pro.perfectproduct.cramin.util.Hashing
import pro.perfectproduct.cramin.util.Log
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

class DownloadException(message: String, val checksumMismatch: Boolean = false) : Exception(message)

/**
 * Скачивание APK и `.sha256` в `cacheDir/updates/` с прогрессом и отменой (через отмену корутины);
 * проверка SHA-256, при несовпадении файл удаляется (SPEC §12.4 п. 4).
 */
class ApkDownloader(private val http: OkHttpClient, private val cacheDir: File) {

    suspend fun download(release: ReleaseInfo, onProgress: (Long, Long) -> Unit = { _, _ -> }): File = withContext(Dispatchers.IO) {
        val dir = File(cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { if (it.name != release.apkName) it.delete() }
        val target = File(dir, release.apkName)
        val expected = release.sha256Url?.let { fetchText(it) }?.let { UpdateChecker.parseSha256(it) }
            ?: throw DownloadException("no checksum")
        if (target.isFile && Hashing.sha256Hex(target) == expected) return@withContext target
        try {
            http.newCall(Request.Builder().url(release.apkUrl).header("User-Agent", "Cramin").build()).useCancellable { resp ->
                if (!resp.isSuccessful) throw DownloadException("HTTP ${resp.code}")
                val total = resp.body.contentLength().takeIf { it > 0 } ?: release.apkSize
                var done = 0L
                resp.body.byteStream().use { input ->
                    target.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            onProgress(done, total)
                        }
                    }
                }
            }
        } catch (e: IOException) {
            target.delete()
            throw DownloadException(e.javaClass.simpleName)
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
        val actual = Hashing.sha256Hex(target)
        if (actual != expected) {
            target.delete()
            Log.w(TAG, "checksum mismatch")
            throw DownloadException("checksum mismatch", checksumMismatch = true)
        }
        target
    }

    private suspend fun fetchText(url: String): String? = try {
        http.newCall(Request.Builder().url(url).header("User-Agent", "Cramin").build()).useCancellable { if (it.isSuccessful) it.body.string() else null }
    } catch (e: IOException) {
        null
    }

    companion object {
        private const val TAG = "Update"
    }
}
