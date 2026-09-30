package pro.perfectproduct.cramin.live

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipInputStream

/**
 * SPEC §14.4: ключ не попадает в APK. Распаковывает собранный debug-APK и ищет первые 12 символов
 * ключа во всех записях (dex, ресурсы, assets, манифест). Ключ в вывод не попадает.
 */
class ApkHasNoSecretsTest {
    @Test
    fun debugApkDoesNotContainKeyPrefix() {
        val key = LiveEnv.requireKey()
        val apkPath = System.getProperty("cramin.debugApk")
        assumeTrue("cramin.debugApk не задан", apkPath != null)
        val apk = File(apkPath!!)
        assertTrue("APK не собран: $apkPath", apk.isFile)
        val needle = key.substring(0, 12).toByteArray(Charsets.UTF_8)
        val offenders = ArrayList<String>()
        var entries = 0
        ZipInputStream(apk.inputStream().buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                entries++
                val bytes = zip.readBytes()
                if (indexOf(bytes, needle) >= 0) offenders += e.name
                zip.closeEntry()
            }
        }
        assertTrue("APK пустой", entries > 10)
        assertFalse("префикс ключа найден в APK: $offenders", offenders.isNotEmpty())
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || hay.size < needle.size) return -1
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
