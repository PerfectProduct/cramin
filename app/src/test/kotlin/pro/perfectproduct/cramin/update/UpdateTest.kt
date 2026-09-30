package pro.perfectproduct.cramin.update

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pro.perfectproduct.cramin.util.Hashing
import pro.perfectproduct.cramin.util.Log

/** SPEC §14.1: сравнение версий, разбор ответа GitHub, проверка SHA-256. */
class UpdateTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private val server = MockWebServer()

    @Before
    fun setUp() {
        Log.install { _, _, _, _ -> }
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
        Log.install(null)
    }

    private fun releaseJson(tag: String, apkUrl: String, shaUrl: String?, size: Long = 1234) = """
        {"tag_name":"$tag","name":"Cramin $tag","body":"- fix A\n- fix B","draft":false,"prerelease":false,
         "assets":[{"name":"cramin-$tag.apk","browser_download_url":"$apkUrl","size":$size}
         ${if (shaUrl != null) ",{\"name\":\"cramin-$tag.apk.sha256\",\"browser_download_url\":\"$shaUrl\",\"size\":100}" else ""}]}
    """.trimIndent()

    @Test
    fun parsesTagAndAssets() {
        val info = UpdateChecker.parse(releaseJson("v0.1.42", "https://x/apk", "https://x/sha"))!!
        assertEquals(42, info.versionCode)
        assertEquals("0.1.42", info.versionName)
        assertEquals("cramin-v0.1.42.apk", info.apkName)
        assertEquals("https://x/sha", info.sha256Url)
        assertTrue(info.notes.contains("fix A"))
        assertNull(UpdateChecker.parse("""{"tag_name":"nightly","assets":[]}"""))
        assertNull(UpdateChecker.parse("not json"))
        assertEquals(7, UpdateChecker.versionCodeFromTag("v0.1.7"))
        assertNull(UpdateChecker.versionCodeFromTag("0.1.7"))
        assertEquals("ab12", UpdateChecker.parseSha256("AB12" + "0".repeat(60) + "  cramin.apk")!!.substring(0, 4))
        assertNull(UpdateChecker.parseSha256("garbage"))
    }

    @Test
    fun compareWithCurrentVersion() = runTest {
        server.enqueue(MockResponse(body = releaseJson("v0.1.10", "https://x/apk", null)))
        server.enqueue(MockResponse(body = releaseJson("v0.1.11", "https://x/apk", null)))
        server.enqueue(MockResponse(code = 404))
        server.enqueue(MockResponse(code = 500))
        val checker = UpdateChecker(OkHttpClient(), currentVersionCode = 10, url = server.url("/latest").toString())
        assertTrue(checker.check() is UpdateCheck.UpToDate)
        val available = checker.check()
        assertTrue(available is UpdateCheck.Available && available.release.versionCode == 11)
        assertTrue(checker.check() is UpdateCheck.UpToDate) // релизов ещё нет
        assertTrue(checker.check() is UpdateCheck.Error)
    }

    @Test
    fun downloadVerifiesSha256AndDeletesOnMismatch() = runTest {
        val apkBytes = ByteArray(50_000) { (it % 251).toByte() }
        val hex = Hashing.sha256Hex(apkBytes)
        server.enqueue(MockResponse(body = "$hex  cramin-v0.1.5.apk\n"))
        server.enqueue(MockResponse.Builder().body(okio.Buffer().write(apkBytes)).build())
        val downloader = ApkDownloader(OkHttpClient(), tmp.root)
        val release = ReleaseInfo("v0.1.5", 5, "0.1.5", "", server.url("/apk").toString(), "cramin-v0.1.5.apk", apkBytes.size.toLong(), server.url("/sha").toString())
        val progress = ArrayList<Long>()
        val file = downloader.download(release) { done, _ -> progress += done }
        assertTrue(file.isFile)
        assertEquals(apkBytes.size.toLong(), file.length())
        assertTrue(progress.last() == apkBytes.size.toLong())

        // Повреждённый файл: контрольная сумма не совпадает, файл удалён.
        server.enqueue(MockResponse(body = "${"0".repeat(64)}  cramin-v0.1.6.apk\n"))
        server.enqueue(MockResponse.Builder().body(okio.Buffer().write(apkBytes)).build())
        val bad = release.copy(apkName = "cramin-v0.1.6.apk")
        try {
            downloader.download(bad)
            fail()
        } catch (e: DownloadException) {
            assertTrue(e.checksumMismatch)
        }
        assertFalse(java.io.File(tmp.root, "updates/cramin-v0.1.6.apk").exists())
    }
}
