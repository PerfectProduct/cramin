package pro.perfectproduct.cramin.pipeline

import kotlinx.serialization.json.*
import pro.perfectproduct.cramin.llm.LlmJson
import pro.perfectproduct.cramin.util.Hashing
import pro.perfectproduct.cramin.llm.ConsolidateItemInput
import pro.perfectproduct.cramin.llm.ConsolidateOccurrenceInput
import pro.perfectproduct.cramin.llm.ConsolidateResponse

/** Смысл карточки: перевод и индексы единиц (в `MergedCard.units`), которые к нему относятся. */
data class SenseDraft(val translation: String, val unitIndices: List<Int>)

/**
 * Консолидация смыслов (SPEC §6.8): пакеты до 40 лемм, разбор ответа, инвариант «каждый id ровно
 * один раз», фолбэк «каждый различный перевод — отдельный смысл», сортировка по числу вхождений.
 */
object Consolidation {
    const val BATCH_SIZE = 40

    fun batches(cards: List<MergedCard>): List<List<MergedCard>> =
        cards.filter { it.needsConsolidation }.chunked(BATCH_SIZE)

    /** Сопоставление id вхождения → (lemmaKey, индекс единицы). Идентификаторы уникальны в пакете. */
    class Batch(val items: List<ConsolidateItemInput>, val ids: Map<Int, Pair<String, Int>>)

    fun buildBatch(cards: List<MergedCard>, sentence: (Int) -> String?): Batch {
        val ids = HashMap<Int, Pair<String, Int>>()
        var next = 1
        val items = cards.map { card ->
            ConsolidateItemInput(
                k = card.lemmaKey,
                l = card.lemma,
                p = card.pos.name,
                o = card.units.mapIndexed { i, u ->
                    val id = next++
                    ids[id] = card.lemmaKey to i
                    ConsolidateOccurrenceInput(id = id, g = u.translation, s = sentence(u.sentenceIdx).orEmpty())
                },
            )
        }
        return Batch(items, ids)
    }

    private fun inputHash(batch: Batch): String = Hashing.sha256Hex(
        Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ConsolidateItemInput.serializer()), batch.items).toByteArray(Charsets.UTF_8))

    /** Extra field is ignored by older readers; no new DB schema and no duplicate source text. */
    fun encodeCached(batch: Batch, response: ConsolidateResponse): String {
        val root = Json.encodeToJsonElement(ConsolidateResponse.serializer(), response).jsonObject
        return JsonObject(root + ("cacheInputSha256" to JsonPrimitive(inputHash(batch)))).toString()
    }

    internal fun readCached(raw: String, batch: Batch, legacyBatch: Batch?, legacy: Boolean): ConsolidateResponse {
        val root = LlmJson.parse<JsonObject>(raw)
        val hash = root["cacheInputSha256"]?.jsonPrimitive?.content
        if (hash != null) {
            if (hash != inputHash(batch)) throw ConsolidationCacheInvalid()
        } else if (legacy && legacyBatch?.items != batch.items) {
            // v4 changed translation grouping. Batch-local occurrence IDs cannot be guessed after a shift.
            throw ConsolidationCacheInvalid()
        }
        return LlmJson.parse(raw)
    }

    /**
     * Применяет ответ к пакету. Для каждой леммы: если её id встречаются в ответе ровно по одному
     * разу и все — берём смыслы модели; иначе фолбэк. `response == null` — фолбэк для всех.
     */
    fun apply(cards: List<MergedCard>, batch: Batch, response: ConsolidateResponse?): Map<String, List<SenseDraft>> {
        val byKey = response?.items?.associateBy { it.k }.orEmpty()
        return cards.associate { card ->
            val item = byKey[card.lemmaKey]
            val expected = batch.ids.filterValues { it.first == card.lemmaKey }.keys
            val senses = item?.let { it ->
                val seen = HashSet<Int>()
                var valid = true
                val drafts = ArrayList<SenseDraft>()
                for (s in it.senses) {
                    val idx = ArrayList<Int>()
                    for (id in s.ids) {
                        if (id !in expected || !seen.add(id)) {
                            valid = false
                            break
                        }
                        idx += batch.ids.getValue(id).second
                    }
                    if (!valid) break
                    val g = s.g.trim()
                    if (g.isEmpty() || idx.isEmpty()) {
                        valid = false
                        break
                    }
                    drafts += SenseDraft(g, idx)
                }
                if (valid && seen.size == expected.size) drafts else null
            }
            card.lemmaKey to sort(senses ?: fallback(card), card)
        }
    }

    /** Фолбэк (SPEC §6.8): каждый различный перевод — отдельный смысл. */
    fun fallback(card: MergedCard): List<SenseDraft> =
        card.translations.map { (key, idx) -> SenseDraft(card.displayTranslation(key), idx) }

    /** Карточка с одним переводом: один смысл без вызова модели (SPEC §6.7). */
    fun single(card: MergedCard): List<SenseDraft> = fallback(card)

    /** Сортировка по числу вхождений, при равенстве — по самому раннему вхождению. */
    fun sort(senses: List<SenseDraft>, card: MergedCard): List<SenseDraft> =
        senses.sortedWith(
            compareByDescending<SenseDraft> { it.unitIndices.size }
                .thenBy { s -> s.unitIndices.minOf { card.units[it].sentenceIdx } },
        )
}
