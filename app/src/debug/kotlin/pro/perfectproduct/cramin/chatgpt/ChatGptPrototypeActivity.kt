package pro.perfectproduct.cramin.chatgpt

import android.app.Application
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*

/** Separate launcher entry, compiled and declared exclusively in debug. Synthetic probe only. */
class ChatGptPrototypeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val model = ViewModelProvider(this)[PrototypeModel::class.java]
        setContent {
            val status by model.status.collectAsState()
            val busy by model.busy.collectAsState()
            val probe by model.probe.state.collectAsState()
            val idle = !busy && !probe.busy
            var picker by remember { mutableStateOf(false) }
            val reportJson = remember { Json { prettyPrint = true } }
            var copied by remember(probe.history) { mutableStateOf(false) }
            var showHistory by remember { mutableStateOf(false) }
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(24.dp).statusBarsPadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("ChatGPT access • debug", style = MaterialTheme.typography.titleLarge)
                        Text("Каталог аккаунта и ручные проверки TRANSLATE strict JSON Schema. Каждое нажатие «Отправить» расходует лимит плана или доступные кредиты ChatGPT.")
                        Text(status)
                        Button(enabled = idle && model.available, onClick = {
                            model.begin(false, false, ::openBrowser)
                        }) { Text("Continue with ChatGPT") }
                        Text(probe.catalogStatus)
                        Button(enabled = idle && model.available && probe.available, onClick = { model.loadCatalog() }) {
                            Text("Получить каталог аккаунта")
                        }
                        Box {
                            OutlinedButton(enabled = idle && probe.models.isNotEmpty(), onClick = { picker = true }) {
                                Text(probe.selected?.let { "${it.displayName} • ${it.slug}" } ?: "Выбрать модель")
                            }
                            DropdownMenu(expanded = picker, onDismissRequest = { picker = false }) {
                                for (item in probe.models) DropdownMenuItem(text = { Text("${item.displayName} • ${item.slug}") },
                                    onClick = { model.probe.select(item.slug); picker = false })
                            }
                        }
                        Text("EN → RU: ${SchemaProbe.SENTENCE}\nРеальная схема Cramin: translation_segments. Короткий вход не гарантирует лимит расхода.")
                        Button(enabled = !busy && model.available && probe.canSend, onClick = { model.runProbe() }) {
                            Text("Отправить запрос • расход плана/кредитов")
                        }
                        if (probe.busy) Text("Выполняется операция…")
                        Text("После завершения можно отправить следующий запрос вручную. Одновременно выполняется один запрос; автоматических повторов нет.")
                        probe.report?.let { report ->
                            if (report.string("status") == "invalid_result" && report["diagnostic"] == null)
                                Text("Старый отчёт без расширенной диагностики. Если исходный ответ не сохранён, новую диагностику задним числом восстановить нельзя.")
                            Text(if (report.string("event") == "response.completed" && report["schema_valid"] == JsonPrimitive(true))
                                "Успех: response.completed; ответ валиден по TRANSLATE." else "Успех не подтверждён.")
                            SelectionContainer { Text(reportJson.encodeToString(JsonObject.serializer(), report)) }
                            Text("Стоимость USD неизвестна. Отсутствующий usage обозначается null.")
                        }
                        Text("История: ${probe.history.size} попыток. Старые маркеры и отчёты сохранены; прежний одноразовый запрет больше не действует.")
                        if (probe.history.isNotEmpty()) {
                            OutlinedButton(onClick = {
                                getSystemService(ClipboardManager::class.java).setPrimaryClip(
                                    ClipData.newPlainText("Cramin ChatGPT debug", probeHistoryReport(probe.history)))
                                copied = true
                            }) { Text("Копировать отчёт для архитектора") }
                            if (copied) Text("История скопирована в буфер обмена.")
                            OutlinedButton(onClick = { showHistory = !showHistory }) {
                                Text(if (showHistory) "Скрыть историю" else "Показать историю")
                            }
                            if (showHistory) for (item in probe.history.asReversed()) {
                                Text("${item.string("started_at") ?: "Время старой попытки неизвестно"} • ${item.string("model") ?: "slug неизвестен"} • ${item.string("status")}")
                                SelectionContainer { Text(reportJson.encodeToString(JsonObject.serializer(), item)) }
                            }
                        }
                        HorizontalDivider()
                        Button(enabled = idle && model.available, onClick = {
                            model.begin(true, false, ::openBrowser)
                        }) { Text("Локальная проверка браузера") }
                        Button(enabled = idle && model.available, onClick = {
                            model.begin(true, true, ::openBrowser)
                        }) { Text("Локально: неверный state") }
                        Button(enabled = busy, onClick = { model.cancel() }) { Text("Отменить вход") }
                        Button(enabled = idle && model.available, onClick = { model.disconnect() }) { Text("Удалить локальные токены") }
                        Text("Если процесс потерян во время входа, начните вход заново. История inference сохраняется. После потери процесса незавершённая попытка получает статус «результат неизвестен»; повторная отправка возможна только вручную. Прототип хранит одну регистрацию. Для другого аккаунта нужна отдельная регистрация в будущей версии.")
                    }
                }
            }
        }
    }
    private fun openBrowser(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
    }
}

class PrototypeModel(application: Application) : AndroidViewModel(application) {
    private val vault = CredentialVault(application)
    private val sessions = ChatGptSessionManager(application)
    private val trace = SyntheticTrace(application.noBackupFilesDir)
    internal val probe = SchemaProbe(ProbeHistoryStore(application.noBackupFilesDir),
        session = { sessions.session() },
        client = { s, m -> ResponsesLlmClient({ s }, m, syntheticDiagnostics = trace::record) })
    val status = MutableStateFlow("Готово")
    val busy = MutableStateFlow(false)
    var available = true
        private set
    private var job: Job? = null
    private var listener: LoopbackListener? = null
    private var attempt: OAuthAttempt? = null
    private val login = ChatGptLogin(application)
    init {
        try {
            vault.hostId()
            val record = vault.read()
            if (record["pending"] == JsonPrimitive(true) && !loginPending(record)) {
                vault.update { current -> if (!loginPending(current)) JsonObject(current - setOf("pending", "pending_id", "pending_until")) else current }
                status.value = "Процесс был потерян: предыдущий вход прекращён. Начните заново."
            } else status.value = accountStatus(record)
        } catch (_: Exception) {
            available = false
            status.value = "Защищённое хранилище недоступно. Вход отключён; данные не сброшены."
        }
    }
    private fun accountStatus(record: JsonObject): String = when {
        record["tokens"] == null -> "Готово. Аккаунт не подключён."
        (record["expires_at"]?.jsonPrimitive?.longOrNull ?: 0) <= System.currentTimeMillis() / 1000 ->
            "Сохранённый вход истёк. Повторите вход; сессия будет обновлена при следующем запросе."
        record["plan_enabled"] == JsonPrimitive(true) -> "ID token проверен; scopes плана выданы. Каталог и результат проверки показаны ниже."
        else -> "Вход сохранён; обязательные scopes плана отсутствуют. Использование плана отключено."
    }
    fun begin(synthetic: Boolean, wrongState: Boolean, browser: (String) -> Unit) {
        if (busy.value || probe.state.value.busy || !available) return
        probe.invalidateCatalog()
        if (!synthetic) {
            busy.value = true
            job = viewModelScope.launch {
                try { login.run(browser); status.value = sessions.checkConnection() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { status.value = chatGptConnectionError(e) }
                finally { busy.value = false }
            }
            return
        }
        try {
            val record = vault.read()
            check(!loginPending(record))
            val host = vault.hostId()
            val server = LoopbackListener(synthetic)
            listener = server
            val tx = OAuthAttempt(server.redirectUri,
                if (synthetic) DYNAMIC_CLIENT else record.string("client_id") ?: DYNAMIC_CLIENT,
                if (synthetic) null else record.string("subject"))
            attempt = tx

            busy.value = true
            status.value = if (synthetic) "Ожидается синтетический callback…" else "Вход открыт в системном браузере…"
            job = viewModelScope.launch {
                try {
                    val callback = withContext(Dispatchers.IO) { server.receive(tx, wrongState) }
                    when (val result = tx.consume(callback)) {
                        Callback.Denied -> status.value = if (synthetic) "Локальная проверка: callback/state проверены; access_denied. Сеть OpenAI не использовалась."
                            else "Вход отменён в браузере."
                        is Callback.Code -> {
                            check(!synthetic)
                            // Retain issued ID even if code exchange fails, including invalid_grant.
                            vault.update { JsonObject(it + ("client_id" to JsonPrimitive(result.clientId))) }
                            status.value = "Проверка ID token и выданных scopes…"
                            val validated = OAuthExchange().exchange(tx, result)
                            ensureActive()
                            vault.write(JsonObject(validated + ("host_id" to JsonPrimitive(host))))
                            status.value = accountStatus(validated)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // No arbitrary exception text: malformed JWT/JSON can embed credentials in messages.
                    status.value = if (synthetic && wrongState && e is IllegalStateException && e.message == "callback_state") "Локальная проверка: callback отклонён (неверный state)."
                        else "Вход не прошёл проверку или сеть недоступна. Начните заново; регистрация сохранена."
                } finally {
                    server.close()
                    listener = null; attempt = null; busy.value = false
                    try { /* Synthetic callback never owns or changes the real session. */ } catch (_: Exception) {
                        available = false; status.value = "Ошибка защищённого хранилища. Вход отключён."
                    }
                }
            }
            browser(if (synthetic) server.syntheticStart else tx.authorizationUrl(host,
                (record["tokens"] as? JsonObject)?.string("id_token")))
        } catch (_: Exception) { cancel(); status.value = "Не удалось открыть браузер или защищённое хранилище." }
    }
    fun cancel() {
        login.close(); attempt?.cancel(); listener?.close(); job?.cancel()
        status.value = "Вход отменён."
    }
    fun disconnect() {
        if (busy.value || probe.state.value.busy) return
        probe.invalidateCatalog()
        try { vault.clearTokens(); status.value = "Локальные токены удалены. Регистрация и host ID сохранены." }
        catch (_: Exception) { available = false; status.value = "Ошибка защищённого хранилища." }
    }
    fun loadCatalog() {
        if (busy.value || !available) return
        viewModelScope.launch { probe.loadCatalog() }
    }
    fun runProbe() {
        if (busy.value || !available) return
        viewModelScope.launch { probe.runManual() }
    }
    override fun onCleared() { cancel(); super.onCleared() }
}
