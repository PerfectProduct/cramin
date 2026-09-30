package pro.perfectproduct.cramin.llm

import kotlinx.serialization.json.JsonObject

/** Роли моделей (SPEC §6.12). */
enum class ModelRole(val key: String) {
    BRIEF("brief"),
    TRANSLATE("translate"),
    EXTRACT("extract"),
    CONSOLIDATE("consolidate"),
    STT("stt"),
    ;

    /** Текстовые роли требуют structured outputs; STT — нет. */
    val isText: Boolean get() = this != STT

    companion object {
        fun fromKey(key: String): ModelRole? = entries.firstOrNull { it.key == key }
    }
}

data class LlmRequest(
    val role: ModelRole,
    val model: String,
    /** Статичный системный промпт идёт первым сообщением — так работает префиксный кэш (SPEC §6.3). */
    val system: String,
    val user: String,
    val schemaName: String,
    val schema: JsonObject,
    val temperature: Double?,
    val maxTokens: Int?,
)

data class LlmUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    /** Фактическая стоимость из `usage.cost`; null, если провайдер её не отдал. */
    val costUsd: Double?,
) {
    operator fun plus(other: LlmUsage) = LlmUsage(
        promptTokens + other.promptTokens,
        completionTokens + other.completionTokens,
        if (costUsd == null && other.costUsd == null) null else (costUsd ?: 0.0) + (other.costUsd ?: 0.0),
    )

    companion object {
        val ZERO = LlmUsage(0, 0, null)
    }
}

data class LlmResponse(
    /** Содержимое ответа — строго JSON по схеме запроса. */
    val content: String,
    /** `stop`, `length`, … — как вернул провайдер. */
    val finishReason: String?,
    val usage: LlmUsage,
    val model: String,
) {
    val truncated: Boolean get() = finishReason == "length"
}

/** Единственный контракт стадий с моделью (SPEC §5.2): фейк в тестах, OpenRouter в проде. */
interface LlmClient {
    suspend fun complete(request: LlmRequest): LlmResponse
}

/**
 * Ошибки вызова модели. Сообщения технические (без текстов документов);
 * пользователю показывается отдельный русский текст по классу ошибки.
 */
sealed class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Сеть недоступна или оборвалась после всех ретраев. */
    class Network(message: String, cause: Throwable? = null) : LlmException(message, cause)

    /** 401/403: ключ неверный или отозван. */
    class Auth(val status: Int) : LlmException("auth failed: HTTP $status")

    /** 402: закончился кредит. */
    class Payment : LlmException("payment required: HTTP 402")

    /** 429 после всех ретраев. */
    class RateLimited : LlmException("rate limited after retries")

    /** 5xx после всех ретраев. */
    class Server(val status: Int) : LlmException("server error: HTTP $status")

    /** 4xx, которых не ждём: модель не найдена, параметры не поддерживаются и т. п. */
    class BadRequest(val status: Int, val detail: String) : LlmException("bad request: HTTP $status: $detail")

    /** Ответ не JSON или не по схеме. */
    class InvalidResponse(val reason: String) : LlmException("invalid response: $reason")

    /** Бюджет живого прогона исчерпан (только тесты). */
    class BudgetExceeded(val spentUsd: Double, val budgetUsd: Double) :
        LlmException("live budget exceeded: spent $spentUsd of $budgetUsd USD")
}
