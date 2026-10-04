package pro.perfectproduct.cramin.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Разбор JSON-ответов стадий. Поля — как в схемах SPEC §6.4–§6.8. */
object LlmJson {
    val lenient: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    val strict: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /** Модели иногда заворачивают JSON в ```json … ```; срезаем ограждение. */
    fun stripFences(content: String): String {
        val t = content.trim()
        if (!t.startsWith("```")) return t
        val body = t.removePrefix("```json").removePrefix("```JSON").removePrefix("```")
        return body.removeSuffix("```").trim()
    }

    inline fun <reified T> parse(content: String): T = lenient.decodeFromString<T>(stripFences(content))
}

@Serializable
data class GlossaryEntry(val src: String, val tgt: String, val note: String? = null)

@Serializable
data class Brief(
    val title: String,
    val emoji: String,
    val summary: String,
    val domain: String,
    val register: String,
    val glossary: List<GlossaryEntry> = emptyList(),
    val subtopics: List<String> = emptyList(),
)

@Serializable
data class TranslateResponse(val seg: List<TranslatedSegment> = emptyList())

@Serializable
data class TranslatedSegment(val from: Int, val to: Int, val t: String)

@Serializable
data class ExtractResponse(val u: List<ExtractedUnit> = emptyList())

@Serializable
data class ExtractedUnit(
    val i: Int,
    val f: String,
    val l: String,
    val lv: String? = null,
    val p: String,
    val g: String,
    val ft: String? = null,
)

@Serializable
data class ConsolidateResponse(val items: List<ConsolidatedItem> = emptyList())

@Serializable
data class ConsolidatedItem(val k: String, val senses: List<ConsolidatedSense> = emptyList())

@Serializable
data class ConsolidatedSense(val g: String, val ids: List<Int> = emptyList(), val category: pro.perfectproduct.cramin.data.db.TopicCategory? = null)

/** Вход консолидации (SPEC §6.8): `{k, l, p, o: [{id, g, s}]}`. */
@Serializable
data class ConsolidateItemInput(val k: String, val l: String, val p: String, val o: List<ConsolidateOccurrenceInput>)

@Serializable
data class ConsolidateOccurrenceInput(val id: Int, val g: String, val s: String)

/** Сохранённый результат стадии перевода одной секции (Job.responseJson). */
@Serializable
data class StoredTranslation(val seg: List<TranslatedSegment>)

/** Сохранённый результат стадии извлечения одного чанка (Job.responseJson): сырые единицы всех подвызовов. */
@Serializable
data class StoredExtraction(val u: List<ExtractedUnit>)

/** Сохранённый результат брифа: сам бриф или пометка, что стадия не удалась (фолбэк §6.10). */
@Serializable
data class StoredBrief(val brief: Brief? = null, @SerialName("failed") val failed: Boolean = false)
