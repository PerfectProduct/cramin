package pro.perfectproduct.cramin.chatgpt

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Memory-only PKCE attempt; a shared lease prevents either process replacing credentials mid-refresh. */
internal class ChatGptLogin(context: Context) {
    private val vault = CredentialVault(context)
    private var listener: LoopbackListener? = null
    private var attempt: OAuthAttempt? = null
    private var lease: String? = null

    suspend fun run(openBrowser: (String) -> Unit) {
        val id = randomValue()
        val host = withContext(Dispatchers.IO) { vault.hostId() }
        val record = withContext(Dispatchers.IO) {
            vault.update { old ->
                check(!loginPending(old)) { "sign_in_in_progress" }
                JsonObject(old + mapOf("pending" to JsonPrimitive(true), "pending_id" to JsonPrimitive(id),
                    "pending_until" to JsonPrimitive(System.currentTimeMillis()/1000 + 600)))
            }
        }
        lease = id
        try {
            val server = LoopbackListener(false).also { listener = it }
            val tx = OAuthAttempt(server.redirectUri, record.string("client_id") ?: DYNAMIC_CLIENT,
                record.string("subject")).also { attempt = it }
            openBrowser(tx.authorizationUrl(host, (record["tokens"] as? JsonObject)?.string("id_token")))
            val callback = withContext(Dispatchers.IO) { server.receive(tx, false) }
            when (val result = tx.consume(callback)) {
                Callback.Denied -> error("sign_in_cancelled")
                is Callback.Code -> {
                    withContext(Dispatchers.IO) { vault.update { current ->
                        check(current.string("pending_id") == id) { "sign_in_superseded" }
                        JsonObject(current + ("client_id" to JsonPrimitive(result.clientId)))
                    } }
                    val validated = OAuthExchange().exchange(tx, result)
                    currentCoroutineContext().ensureActive()
                    withContext(Dispatchers.IO) { vault.update { current ->
                        check(current.string("pending_id") == id && loginPending(current)) { "sign_in_superseded" }
                        JsonObject(validated + ("host_id" to JsonPrimitive(host)))
                    } }
                }
            }
        } finally {
            close()
            withContext(NonCancellable + Dispatchers.IO) {
                vault.update { current ->
                    if (current.string("pending_id") == id) JsonObject(current - setOf("pending", "pending_id", "pending_until")) else current
                }
            }
            lease = null
        }
    }
    fun close() { attempt?.cancel(); listener?.close(); listener = null; attempt = null }
}
internal fun loginPending(record: JsonObject): Boolean = record["pending"] == JsonPrimitive(true) &&
    (record["pending_until"]?.jsonPrimitive?.longOrNull ?: 0) > System.currentTimeMillis()/1000

/** No arbitrary server/exception text is exposed; token responses may contain credentials. */
fun chatGptConnectionError(error: Throwable): String = when (error) {
    is ResponseFailure -> when (error.httpStatus) {
        401 -> "Сессия ChatGPT отклонена. Повторите вход."
        429 -> "Лимит ChatGPT исчерпан. Проверьте план и повторите позже."
        else -> "ChatGPT недоступен. Проверьте сеть и повторите."
    }
    is java.io.IOException -> "Нет связи с ChatGPT. Проверьте сеть."
    else -> when (error.message) {
        "access_token_missing", "access_token_expired", "session_revoked" -> "Войдите в ChatGPT, чтобы подключить план."
        "plan_permission_missing" -> "При входе не выданы разрешения плана ChatGPT. Повторите вход."
        "sign_in_in_progress" -> "Вход уже открыт в браузере. Завершите его или отмените."
        "invalid_client" -> "Регистрация Cramin отклонена. Повторите вход."
        "sign_in_cancelled" -> "Вход отменён."
        else -> "Не удалось подключить ChatGPT. Проверьте сеть и повторите вход."
    }
}
