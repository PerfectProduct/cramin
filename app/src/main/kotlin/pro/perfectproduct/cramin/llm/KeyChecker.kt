package pro.perfectproduct.cramin.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import pro.perfectproduct.cramin.util.Log
import java.io.IOException

/** Результат `GET /api/v1/key` (SPEC §9.7): остаток кредита, если отдан. Метку ключа не храним. */
sealed interface KeyCheck {
    data class Valid(val usageUsd: Double?, val limitUsd: Double?, val limitRemainingUsd: Double?, val isFreeTier: Boolean?) : KeyCheck
    data object Invalid : KeyCheck
    data class Error(val detail: String) : KeyCheck
}

class KeyChecker(
    private val http: OkHttpClient,
    private val baseUrl: String = OpenRouterClient.DEFAULT_BASE_URL,
) {
    suspend fun check(key: String): KeyCheck = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$baseUrl/key")
                .header("Authorization", "Bearer ${key.trim()}")
                .header("HTTP-Referer", OpenRouterClient.REFERER)
                .header("X-Title", OpenRouterClient.TITLE)
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body.string()
                when {
                    resp.code == 401 || resp.code == 403 -> KeyCheck.Invalid
                    !resp.isSuccessful -> KeyCheck.Error("HTTP ${resp.code}")
                    else -> {
                        val data = LlmJson.lenient.parseToJsonElement(text).jsonObject["data"]?.jsonObject
                        KeyCheck.Valid(
                            usageUsd = data?.get("usage")?.jsonPrimitive?.doubleOrNull,
                            limitUsd = data?.get("limit")?.jsonPrimitive?.doubleOrNull,
                            limitRemainingUsd = data?.get("limit_remaining")?.jsonPrimitive?.doubleOrNull,
                            isFreeTier = data?.get("is_free_tier")?.jsonPrimitive?.booleanOrNull,
                        )
                    }
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "key check failed: ${e.javaClass.simpleName}")
            KeyCheck.Error(e.javaClass.simpleName)
        } catch (e: IllegalStateException) {
            KeyCheck.Error("bad response")
        }
    }

    companion object {
        private const val TAG = "KeyChecker"

        @Suppress("unused")
        private fun label(text: String): String? = runCatching {
            LlmJson.lenient.parseToJsonElement(text).jsonObject["data"]?.jsonObject?.get("label")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }
}
