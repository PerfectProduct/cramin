package pro.perfectproduct.cramin.chatgpt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import pro.perfectproduct.cramin.llm.*
import pro.perfectproduct.cramin.pipeline.Messages
import pro.perfectproduct.cramin.pipeline.SentenceDraft
import pro.perfectproduct.cramin.util.Lang

internal data class ProbeState(
    val busy: Boolean = false,
    val available: Boolean = true,
    val models: List<AccountModel> = emptyList(),
    val selected: AccountModel? = null,
    val catalogStatus: String = "Каталог ещё не загружен.",
    val history: List<JsonObject> = emptyList(),
) {
    val canSend: Boolean get() = available && !busy && selected != null
    val report: JsonObject? get() = history.lastOrNull()
}

/** Main-thread state machine. Only runManual(), wired to the owner's button, can POST inference. */
internal class SchemaProbe(
    private val store: ProbeHistoryStore,
    private val session: suspend () -> AccessSession,
    private val catalog: suspend (AccessSession) -> List<AccountModel> = { ResponsesLlmClient.listModels(it) },
    private val client: (AccessSession, AccountModel) -> ResponsesLlmClient = { s, m -> ResponsesLlmClient({ s }, m) },
) {
    private val mutable = MutableStateFlow(ProbeState())
    val state = mutable.asStateFlow()

    init {
        try {
            refreshHistory()
        } catch (_: Exception) { storageFailure() }
    }

    fun invalidateCatalog() {
        if (!state.value.busy) mutable.value = state.value.copy(models = emptyList(), selected = null,
            catalogStatus = "Вход изменён. Получите каталог заново.")
    }

    fun select(slug: String) {
        if (state.value.busy) return
        mutable.value = state.value.copy(selected = state.value.models.firstOrNull { it.slug == slug })
    }

    suspend fun loadCatalog() {
        if (state.value.busy || !state.value.available) return
        mutable.value = state.value.copy(busy = true, models = emptyList(), selected = null,
            catalogStatus = "Получение каталога аккаунта… Inference не запускается.")
        try {
            val s = session().also { it.requireUsable() }
            val models = catalog(s)
            mutable.value = state.value.copy(models = models, catalogStatus = if (models.isEmpty())
                "Каталог пуст: доступных моделей нет." else "Каталог получен (${models.size}). Выберите модель.")
        } catch (e: CancellationException) {
            mutable.value = state.value.copy(catalogStatus = "Получение каталога прервано.")
            throw e
        } catch (e: Exception) {
            mutable.value = state.value.copy(catalogStatus = safeProbeFailure(e))
        } finally { mutable.value = state.value.copy(busy = false) }
    }

    suspend fun runManual() {
        if (!state.value.canSend) return
        val selected = state.value.selected ?: return
        mutable.value = state.value.copy(busy = true)
        var attemptId: String? = null
        try {
            // Expiry and request validation happen before a new durable attempt is recorded.
            val s = session().also { it.requireUsable() }
            val request = request(selected)
            ResponsesLlmClient.buildBody(request, selected)
            try {
                attemptId = store.start(selected.slug)
                refreshHistory()
            } catch (_: Exception) { storageFailure(); return }
            val id = attemptId ?: run {
                mutable.value = state.value.copy(catalogStatus = "Другой ручной запрос ещё выполняется.")
                return
            }
            val transport = client(s, selected)
            val result = transport.complete(request)
            val evidence = transport.completionEvidence ?: error("completion_missing")
            // No delta/EOF, partial answer or invalid schema can reach this branch.
            PrototypeSchema.validate(Json.parseToJsonElement(result.content), Schemas.TRANSLATE)
            val report = JsonObject(evidence + mapOf("schema_valid" to JsonPrimitive(true),
                "result" to Json.parseToJsonElement(result.content), "cost_usd" to JsonNull))
            persist(id, report)
        } catch (e: CancellationException) {
            attemptId?.let { persist(it, failed(selected, "unknown",
                "Ожидание прервано: результат неизвестен. Запрос мог расходовать план или кредиты. Автоматического повтора нет.")) }
            throw e
        } catch (e: Exception) {
            if (attemptId != null) persist(attemptId, failed(selected, failureStatus(e), safeProbeFailure(e), (e as? ResponseFailure)?.diagnostic))
            else mutable.value = state.value.copy(catalogStatus = safeProbeFailure(e), models = emptyList(), selected = null)
        } finally { mutable.value = state.value.copy(busy = false) }
    }

    private fun refreshHistory() {
        mutable.value = state.value.copy(history = store.history())
    }

    private fun persist(id: String, report: JsonObject) {
        try { store.finish(id, report); refreshHistory() } catch (_: Exception) { storageFailure() }
    }

    private fun storageFailure() {
        mutable.value = state.value.copy(available = false,
            catalogStatus = "Ошибка хранилища истории. Отправка отключена; существующие данные не сброшены.")
    }

    private fun failed(model: AccountModel, status: String, message: String, diagnostic: ResponseDiagnostic? = null) = buildJsonObject {
        put("model", model.slug); put("status", status); put("schema_valid", false)
        put("message", message); put("usage", diagnostic?.usage ?: JsonNull)
        diagnostic?.let { put("diagnostic", it.toJson()) }
    }

    companion object {
        const val SENTENCE = "Water freezes at zero degrees Celsius."
        fun request(model: AccountModel) = LlmRequest(ModelRole.TRANSLATE, model.slug, Prompts.TRANSLATE,
            Messages.translate(Lang.EN, Lang.RU, null, emptyList(), emptyList(), listOf(SentenceDraft(0, 0, SENTENCE))),
            Schemas.TRANSLATE_NAME, Schemas.TRANSLATE, temperature = null, maxTokens = null)
    }
}

private fun failureStatus(error: Exception): String = when (error) {
    is ResponseFailure -> error.kind.name.lowercase()
    else -> "unconfirmed"
}

/** Allowlisted messages only: no arbitrary exception, server body, ID, header or credential. */
internal fun safeProbeFailure(error: Exception): String = when {
    error is IllegalStateException && error.message == "access_token_expired" ->
        "Токен истёк или скоро истечёт. Повторите вход через Continue with ChatGPT."
    error is IllegalStateException && error.message == "access_token_missing" -> "Сначала выполните вход через Continue with ChatGPT."
    error is IllegalStateException && error.message == "plan_permission_missing" -> "Scopes плана отсутствуют. Повторите вход."
    error is ResponseFailure -> when {
        error.httpStatus == 401 -> "HTTP 401: вход истёк или отозван. Повторите вход через Continue with ChatGPT."
        error.kind == ResponseFailureKind.USAGE_LIMIT -> "Лимит ChatGPT исчерпан. Следующую проверку запускайте вручную после восстановления лимита."
        error.kind == ResponseFailureKind.USAGE_UNAVAILABLE -> "Usage плана недоступен. Завершение не подтверждено."
        error.diagnostic != null -> "Ответ не принят: этап ${error.diagnostic.stage.name.lowercase()}. " +
            if (error.diagnostic.terminalEvent == null) "Terminal event не наблюдался; успех не подтверждён."
            else "Terminal event получен, ответ отвергнут; успех не подтверждён."
        error.httpStatus != null -> "HTTP ${error.httpStatus}; завершение не подтверждено."
        else -> "${error.kind.name}: завершение и валидность ответа не подтверждены."
    }
    else -> "Сеть недоступна или ответ не прошёл проверку. Завершение не подтверждено."
}
