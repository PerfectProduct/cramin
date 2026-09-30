package pro.perfectproduct.cramin.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import pro.perfectproduct.cramin.util.Log
import java.io.IOException

/** Релиз GitHub (SPEC §12.4): версия из тега, заметки, ассеты APK и .sha256. */
data class ReleaseInfo(
    val tagName: String,
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val apkName: String,
    val apkSize: Long,
    val sha256Url: String?,
)

sealed interface UpdateCheck {
    data class UpToDate(val current: Int) : UpdateCheck
    data class Available(val release: ReleaseInfo) : UpdateCheck
    data class Error(val detail: String) : UpdateCheck
}

@Serializable
private data class GhRelease(val tag_name: String = "", val name: String? = null, val body: String? = null, val assets: List<GhAsset> = emptyList(), val draft: Boolean = false, val prerelease: Boolean = false)

@Serializable
private data class GhAsset(val name: String, val browser_download_url: String, val size: Long = 0)

/**
 * `GET /repos/PerfectProduct/cramin/releases/latest` без аутентификации, только по нажатию (SPEC §12.4 п. 1).
 * Тег `v0.1.<N>` → versionCode N; не больше текущего — «У вас последняя версия».
 */
class UpdateChecker(
    private val http: OkHttpClient,
    private val currentVersionCode: Int,
    private val url: String = LATEST_URL,
) {
    suspend fun check(): UpdateCheck = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).header("Accept", "application/vnd.github+json").header("User-Agent", "Cramin").build()
            http.newCall(request).execute().use { resp ->
                if (resp.code == 404) return@withContext UpdateCheck.UpToDate(currentVersionCode)
                if (!resp.isSuccessful) return@withContext UpdateCheck.Error("HTTP ${resp.code}")
                val info = parse(resp.body.string()) ?: return@withContext UpdateCheck.Error("bad release JSON")
                if (info.versionCode <= currentVersionCode) UpdateCheck.UpToDate(currentVersionCode) else UpdateCheck.Available(info)
            }
        } catch (e: IOException) {
            Log.w(TAG, "check failed: ${e.javaClass.simpleName}")
            UpdateCheck.Error(e.javaClass.simpleName)
        }
    }

    companion object {
        private const val TAG = "Update"
        const val LATEST_URL = "https://api.github.com/repos/PerfectProduct/cramin/releases/latest"
        private val json = Json { ignoreUnknownKeys = true }
        private val TAG_REGEX = Regex("^v(\\d+)\\.(\\d+)\\.(\\d+)$")

        /** `v0.1.42` → 42; null для чужого формата. */
        fun versionCodeFromTag(tag: String): Int? = TAG_REGEX.matchEntire(tag.trim())?.groupValues?.get(3)?.toIntOrNull()

        fun parse(text: String): ReleaseInfo? {
            val r = runCatching { json.decodeFromString<GhRelease>(text) }.getOrNull() ?: return null
            if (r.draft) return null
            val code = versionCodeFromTag(r.tag_name) ?: return null
            val apk = r.assets.firstOrNull { it.name.endsWith(".apk") } ?: return null
            val sha = r.assets.firstOrNull { it.name == apk.name + ".sha256" }
            return ReleaseInfo(
                tagName = r.tag_name.trim(),
                versionCode = code,
                versionName = r.tag_name.trim().removePrefix("v"),
                notes = r.body?.trim().orEmpty(),
                apkUrl = apk.browser_download_url,
                apkName = apk.name,
                apkSize = apk.size,
                sha256Url = sha?.browser_download_url,
            )
        }

        /** Формат `sha256sum`: `<hex>  <filename>`; возвращает hex или null. */
        fun parseSha256(text: String): String? = text.trim().split(Regex("\\s+")).firstOrNull()?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
    }
}
