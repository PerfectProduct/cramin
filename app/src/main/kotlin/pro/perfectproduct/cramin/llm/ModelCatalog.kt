package pro.perfectproduct.cramin.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import pro.perfectproduct.cramin.util.Clock
import pro.perfectproduct.cramin.util.Log
import java.io.File
import java.io.IOException

/** Снимок каталога моделей OpenRouter с моментом загрузки. */
class CatalogSnapshot(val models: List<CatalogModel>, val fetchedAt: Long) : CatalogView {
    private val byId = models.associateBy { it.id }
    override fun find(modelId: String): CatalogModel? = byId[modelId]

    /** Текстовые модели со structured outputs — для выбора модели роли в настройках (SPEC §9.7). */
    fun structuredOutputModels(): List<CatalogModel> = models.filter { it.supportsStructuredOutputs && "text" in it.outputModalities }
}

/**
 * `GET /api/v1/models` с кэшем на 24 часа (SPEC §6.12). Публичный эндпоинт; ключ не нужен.
 * При ошибке сети возвращает устаревший кэш любого возраста или null.
 */
class ModelCatalog(
    private val http: OkHttpClient,
    private val cacheFile: File,
    private val clock: Clock,
    private val baseUrl: String = OpenRouterClient.DEFAULT_BASE_URL,
) {
    private val mutex = Mutex()

    @Volatile
    private var memory: CatalogSnapshot? = null

    /** Актуальный снимок: из памяти/файла, если ему меньше [TTL_MS], иначе с сети; `force` — всегда с сети. */
    suspend fun get(force: Boolean = false): CatalogSnapshot? = mutex.withLock {
        val cached = memory ?: loadFile()?.also { memory = it }
        if (!force && cached != null && clock.now() - cached.fetchedAt < TTL_MS) return cached
        val fresh = fetch()
        if (fresh != null) {
            memory = fresh
            return fresh
        }
        return cached
    }

    /** Что есть под рукой без сети (для валидации конфига в офлайне). */
    suspend fun cached(): CatalogSnapshot? = mutex.withLock { memory ?: loadFile()?.also { memory = it } }

    private suspend fun fetch(): CatalogSnapshot? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$baseUrl/models").header("HTTP-Referer", OpenRouterClient.REFERER).header("X-Title", OpenRouterClient.TITLE).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "catalog HTTP ${resp.code}")
                    return@withContext null
                }
                val text = resp.body.string()
                val models = parse(text)
                if (models.isEmpty()) return@withContext null
                cacheFile.parentFile?.mkdirs()
                cacheFile.writeText(text)
                val now = clock.now()
                cacheFile.setLastModified(now)
                Log.i(TAG, "catalog fetched: ${models.size} models")
                CatalogSnapshot(models, now)
            }
        } catch (e: IOException) {
            Log.w(TAG, "catalog fetch failed: ${e.javaClass.simpleName}")
            null
        } catch (e: IllegalStateException) {
            Log.w(TAG, "catalog parse failed")
            null
        }
    }

    private fun loadFile(): CatalogSnapshot? {
        if (!cacheFile.isFile) return null
        return runCatching { CatalogSnapshot(parse(cacheFile.readText()), cacheFile.lastModified()) }
            .getOrNull()?.takeIf { it.models.isNotEmpty() }
    }

    companion object {
        private const val TAG = "ModelCatalog"
        const val TTL_MS = 24L * 60 * 60 * 1000
        const val CACHE_FILE_NAME = "models-catalog.json"

        fun parse(text: String): List<CatalogModel> {
            val root = LlmJson.lenient.parseToJsonElement(text).jsonObject
            val data = root["data"]?.jsonArray ?: return emptyList()
            return data.mapNotNull { el -> runCatching { parseModel(el) }.getOrNull() }
        }

        private fun parseModel(el: JsonElement): CatalogModel {
            val o = el.jsonObject
            val pricing = o["pricing"]?.jsonObject
            val top = o["top_provider"]?.jsonObject
            val arch = o["architecture"]?.jsonObject
            fun price(k: String): Double? = pricing?.get(k)?.jsonPrimitive?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
            fun strings(e: JsonElement?): List<String> = e?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            return CatalogModel(
                id = o["id"]?.jsonPrimitive?.contentOrNull ?: error("no id"),
                name = o["name"]?.jsonPrimitive?.contentOrNull ?: "",
                contextLength = o["context_length"]?.jsonPrimitive?.intOrNull ?: top?.get("context_length")?.jsonPrimitive?.intOrNull,
                maxCompletionTokens = top?.get("max_completion_tokens")?.jsonPrimitive?.intOrNull,
                promptPrice = price("prompt"),
                completionPrice = price("completion"),
                supportedParameters = strings(o["supported_parameters"]),
                inputModalities = strings(arch?.get("input_modalities")),
                outputModalities = strings(arch?.get("output_modalities")),
            )
        }
    }
}
