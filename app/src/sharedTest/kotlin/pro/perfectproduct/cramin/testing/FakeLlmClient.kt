package pro.perfectproduct.cramin.testing

import kotlinx.serialization.json.Json
import pro.perfectproduct.cramin.llm.Brief
import pro.perfectproduct.cramin.llm.ConsolidateItemInput
import pro.perfectproduct.cramin.llm.ConsolidateResponse
import pro.perfectproduct.cramin.llm.ConsolidatedItem
import pro.perfectproduct.cramin.llm.ConsolidatedSense
import pro.perfectproduct.cramin.llm.ExtractResponse
import pro.perfectproduct.cramin.llm.ExtractedUnit
import pro.perfectproduct.cramin.llm.GlossaryEntry
import pro.perfectproduct.cramin.llm.LlmClient
import pro.perfectproduct.cramin.llm.LlmException
import pro.perfectproduct.cramin.llm.LlmRequest
import pro.perfectproduct.cramin.llm.LlmResponse
import pro.perfectproduct.cramin.llm.LlmUsage
import pro.perfectproduct.cramin.llm.ModelRole
import pro.perfectproduct.cramin.llm.TranslateResponse
import pro.perfectproduct.cramin.llm.TranslatedSegment
import pro.perfectproduct.cramin.pipeline.Messages
import pro.perfectproduct.cramin.pipeline.SentenceDraft

/**
 * Детерминированный фейк LLM для тестов (SPEC §14.1). Ответы вычисляются из текста запроса:
 *  - brief: заголовок из первых слов, глоссарий из частых длинных слов;
 *  - translate: «перевод» слова — `tr_<слово>`; каждое предложение с idx % [mergeEvery] == 1
 *    объединяется со следующим, чтобы проверять выравнивание сегментов;
 *  - extract: единицы — слова длиной ≥ 4 (иврит ≥ 3); многозначное слово получает разные `g`
 *    по контексту ([polysemy]), чтобы сработала консолидация; в каждый пакет добавляется
 *    служебное слово из стоп-листа для проверки валидации;
 *  - consolidate: группировка вхождений по точному `g`.
 * Стоимость фиксирована: [costPerCall] за вызов — для проверки учёта.
 */
class FakeLlmClient(
    val polysemy: Map<String, Polysemy> = DEFAULT_POLYSEMY,
    val mergeEvery: Int = 4,
    val costPerCall: Double = 0.0001,
) : LlmClient {

    /** Многозначное слово: если предложение содержит одну из [cues], перевод — [cueTranslation], иначе [defaultTranslation]. */
    data class Polysemy(val cues: List<String>, val cueTranslation: String, val defaultTranslation: String)

    val requests = ArrayList<LlmRequest>()

    /** Хук для тестов: вернуть подменённый ответ или бросить исключение; null — обычное поведение. */
    var interceptor: ((request: LlmRequest, index: Int) -> LlmResponse?)? = null

    /** Хук для тестов: ошибка вместо ответа (сеть, 401, «падение» процесса). */
    var errorInjector: ((request: LlmRequest, index: Int) -> Throwable?)? = null

    fun callsFor(role: ModelRole): Int = requests.count { it.role == role }

    override suspend fun complete(request: LlmRequest): LlmResponse {
        val index = requests.size
        synchronized(requests) { requests += request }
        errorInjector?.invoke(request, index)?.let { throw it }
        interceptor?.invoke(request, index)?.let { return it }
        val content = when (request.role) {
            ModelRole.BRIEF -> brief(request.user)
            ModelRole.TRANSLATE -> translate(request.user)
            ModelRole.EXTRACT -> extract(request.user)
            ModelRole.CONSOLIDATE -> consolidate(request.user)
            ModelRole.STT -> throw LlmException.BadRequest(400, "STT is not a chat role")
        }
        return LlmResponse(
            content = content,
            finishReason = "stop",
            usage = LlmUsage(promptTokens = request.user.length / 4 + 1, completionTokens = content.length / 4 + 1, costUsd = costPerCall),
            model = request.model,
        )
    }

    private fun brief(user: String): String {
        val source = Messages.parseHeader(user, "SOURCE") ?: "en"
        val text = user.substringAfter("TEXT:\n", "")
        val words = tokens(text)
        val title = words.take(6).joinToString(" ").ifEmpty { "Untitled" }
        val glossary = words.filter { it.length >= 6 }.groupingBy { it.lowercase() }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(3)
            .map { GlossaryEntry(src = it.key, tgt = "tr_${it.key}", note = null) }
        return json.encodeToString(
            Brief(title = title, emoji = "📘", summary = "Fake summary for a $source text.", domain = "test", register = "neutral", glossary = glossary),
        )
    }

    private fun translate(user: String): String {
        val sentences = Messages.parseSentences(user)
        val segs = ArrayList<TranslatedSegment>()
        var i = 0
        while (i < sentences.size) {
            val s = sentences[i]
            val next = sentences.getOrNull(i + 1)
            if (s.idx % mergeEvery == 1 && next != null && next.idx == s.idx + 1) {
                segs += TranslatedSegment(s.idx, next.idx, fakeTranslate(s.text) + " " + fakeTranslate(next.text))
                i += 2
            } else {
                segs += TranslatedSegment(s.idx, s.idx, fakeTranslate(s.text))
                i++
            }
        }
        return json.encodeToString(TranslateResponse(segs))
    }

    private fun extract(user: String): String {
        val source = Messages.parseHeader(user, "SOURCE") ?: "en"
        val minLen = if (source == "he") 3 else 4
        val units = ArrayList<ExtractedUnit>()
        val seen = HashSet<Pair<String, String>>()
        var stopWordAdded = false
        for (pair in Messages.parsePairs(user)) {
            for (s in pair.sentences) {
                for (raw in s.text.split(Regex("\\s+"))) {
                    val token = raw.trim { !it.isLetter() }
                    if (token.isEmpty() || token.any { !it.isLetter() }) continue
                    val lemma = token.lowercase()
                    if (!stopWordAdded && lemma in STOP_PROBE) {
                        // Служебное слово: должно отсеяться стоп-листом (SPEC §6.6 п. 4).
                        units += ExtractedUnit(i = s.idx, f = token, l = lemma, lv = null, p = "NOUN", g = "tr_$lemma", ft = "tr_$lemma")
                        stopWordAdded = true
                        continue
                    }
                    if (lemma.length < minLen || lemma in STOP_PROBE) continue
                    val g = polysemy[lemma]?.let { p -> if (p.cues.any { s.text.contains(it, ignoreCase = true) }) p.cueTranslation else p.defaultTranslation } ?: "tr_$lemma"

                    val pos = if (source == "en" && (lemma.endsWith("ing") || lemma.endsWith("ed"))) "VERB" else "NOUN"
                    units += ExtractedUnit(
                        i = s.idx, f = token, l = lemma,
                        lv = if (source == "he") lemma + "ָ" else null,
                        p = pos, g = g, ft = "tr_$lemma",
                    )
                }
            }
        }
        return json.encodeToString(ExtractResponse(units))
    }

    private fun consolidate(user: String): String {
        val itemsJson = user.substringAfter("ITEMS:\n", "[]")
        val items = json.decodeFromString<List<ConsolidateItemInput>>(itemsJson)
        return json.encodeToString(
            ConsolidateResponse(
                items.map { item ->
                    ConsolidatedItem(
                        k = item.k,
                        senses = item.o.groupBy { it.g }.map { (g, occ) -> ConsolidatedSense(g, occ.map { it.id }) },
                    )
                },
            ),
        )
    }

    companion object {
        private val json = Json { encodeDefaults = true; explicitNulls = true }

        val DEFAULT_POLYSEMY: Map<String, Polysemy> = mapOf(
            "bank" to Polysemy(cues = listOf("river"), cueTranslation = "берег", defaultTranslation = "банк"),
            "ключ" to Polysemy(cues = listOf("бьёт", "холодн"), cueTranslation = "spring", defaultTranslation = "key"),
            "רשת" to Polysemy(cues = listOf("דגים"), cueTranslation = "net", defaultTranslation = "network"),
        )

        /** Служебные слова, которые фейк подсовывает в пакет и которых нет в «переводе». */
        private val STOP_PROBE = setOf("the", "and", "that", "with", "from", "this", "have", "were", "been", "they", "their", "there", "what", "when", "которые", "потому", "את", "של", "על")

        fun tokens(text: String): List<String> = text.split(Regex("\\s+")).map { it.trim { c -> !c.isLetter() } }.filter { it.isNotEmpty() }

        fun fakeTranslate(sentence: String): String = sentence.split(Regex("\\s+"))
            .map { it.trim { c -> !c.isLetterOrDigit() } }
            .filter { it.isNotEmpty() }
            .joinToString(" ") { "tr_" + it.lowercase() }
            .ifEmpty { "tr_empty" }

        fun sentencesOf(user: String): List<SentenceDraft> = Messages.parseSentences(user)
    }
}
