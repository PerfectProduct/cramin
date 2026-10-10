package pro.perfectproduct.cramin.chatgpt

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*

/** Credentials stay in the vault; only safe connection state is exposed to UI. */
class ChatGptSessionManager internal constructor(private val vault: CredentialStore,
    private val http: OkHttpClient, private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val refreshEndpoint: String = "$ISSUER/api/accounts/oauth/token") {
    constructor(context: Context) : this(CredentialVault(context), prototypeHttp())

    internal val inferenceHttp: OkHttpClient = prototypeHttp().newBuilder()
        .readTimeout(300, java.util.concurrent.TimeUnit.SECONDS).callTimeout(360, java.util.concurrent.TimeUnit.SECONDS).build()

    private val _changes = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val changes: kotlinx.coroutines.flow.StateFlow<Long> = _changes
    internal fun connectionChanged() { _changes.value += 1 }

    internal suspend fun session(rejectedAccessToken: String? = null): AccessSession = withContext(Dispatchers.IO) {
        var revoked = false
        val record = vault.update { saved ->
            check(!loginPending(saved)) { "sign_in_in_progress" }
            check(saved["tokens"] is JsonObject) { "access_token_missing" }
            check(saved["plan_enabled"] == JsonPrimitive(true)) { "plan_permission_missing" }
            val now = nowSeconds()
            if ((saved["expires_at"]?.jsonPrimitive?.longOrNull ?: 0) > now + 120 && (rejectedAccessToken == null ||
                (saved["tokens"] as? JsonObject)?.string("access_token") != rejectedAccessToken)) saved
            else try { refreshRecord(saved, http, now, refreshEndpoint) }
            catch (_: SessionRevoked) { revoked = true; CredentialVault.withoutTokens(saved) }
        }
        if (revoked) error("session_revoked")
        AccessSession.fromRecord(record)
    }

    suspend fun connected(): Boolean = withContext(Dispatchers.IO) {
        val r = vault.read()
        r["plan_enabled"] == JsonPrimitive(true) && r["tokens"] is JsonObject
    }
    suspend fun checkConnection(): String {
        session()
        return "ChatGPT подключён. Сессия готова; обновляется автоматически."
    }
    private val catalogMutex = kotlinx.coroutines.sync.Mutex()
    private var cachedModels: List<AccountModel> = emptyList()
    private var catalogAt = 0L
    suspend fun catalog(force: Boolean = false): List<AccountModel> {
        catalogMutex.lock()
        try {
            // Even cached catalog access keeps a long-lived session refreshed.
            var active = session()
            if (!force && cachedModels.isNotEmpty() && nowSeconds() - catalogAt < 300) return cachedModels
            val models = try { ResponsesLlmClient.listModels(active, http = http) }
                catch (e: ResponseFailure) {
                    if (e.httpStatus != 401) throw e
                    active = session(active.accessToken)
                    ResponsesLlmClient.listModels(active, http = http)
                }
            cachedModels = models; catalogAt = nowSeconds()
            return models
        } finally { catalogMutex.unlock() }
    }

    /** Safe verification output only; token values never leave this component. */
    internal suspend fun verifyRenewal(): JsonObject = withContext(Dispatchers.IO) {
        val before = vault.read()
        val earliest = (before["tokens"] as? JsonObject)?.get("earliest_refresh_at")?.jsonPrimitive?.longOrNull
        if (earliest != null && nowSeconds() < earliest) return@withContext buildJsonObject {
            put("status", "not_yet_allowed"); put("earliest_refresh_at", earliest)
        }
        val oldAccess = (before["tokens"] as? JsonObject)?.string("access_token") ?: error("access_token_missing")
        val renewed = session(oldAccess)
        val after = vault.read()
        buildJsonObject {
            put("status", "renewed"); put("access_changed", oldAccess != renewed.accessToken)
            put("renewal_count", after["renewal_count"] ?: JsonNull)
            put("expires_at", after["expires_at"] ?: JsonNull)
        }
    }

    suspend fun disconnect(): String = withContext(Dispatchers.IO) {
        inferenceHttp.dispatcher.cancelAll()
        cachedModels = emptyList(); catalogAt = 0L
        var confirmed = false
        vault.update { record ->
            val refresh = (record["tokens"] as? JsonObject)?.string("refresh_token")
            if (refresh != null) {
                // Revocation is idempotent. Keep the token under the shared lock for one bounded backoff retry.
                repeat(2) { attempt ->
                    if (!confirmed) {
                        if (attempt > 0) Thread.sleep(250)
                        try {
                            val config = http.newCall(Request.Builder().url("$ISSUER/.well-known/openid-configuration").build())
                                .execute().use { response ->
                                    check(response.isSuccessful)
                                    Json.parseToJsonElement(response.body.string()).jsonObject
                                }
                            check(config.string("issuer") == ISSUER)
                            val endpoint = config.string("revocation_endpoint") ?: error("revocation_metadata")
                            check(endpoint.startsWith("$ISSUER/"))
                            val form = FormBody.Builder().add("token", refresh).add("token_type_hint", "refresh_token")
                                .add("client_id", record.string("client_id") ?: error("invalid_client")).build()
                            confirmed = http.newCall(Request.Builder().url(endpoint).post(form).build()).execute().use { it.code == 200 }
                        } catch (_: Exception) { /* Never log token responses or claim remote revocation. */ }
                    }
                }
            } else confirmed = true
            CredentialVault.withoutTokens(record)
        }
        connectionChanged()
        if (confirmed) "ChatGPT отключён." else "Отключён на устройстве. Отзыв сессии не подтверждён; отключите Cramin в настройках ChatGPT."
    }
}

internal class SessionRevoked : Exception("session_revoked")

/** Caller holds the process and OS vault locks until atomic replacement is durable. */
internal fun refreshRecord(saved: JsonObject, http: OkHttpClient, now: Long,
    endpoint: String = "$ISSUER/api/accounts/oauth/token"): JsonObject {
    val old = saved.getValue("tokens").jsonObject
    val earliest = old["earliest_refresh_at"]?.jsonPrimitive?.longOrNull
    check(earliest == null || now >= earliest) { "refresh_too_early" }
    val client = saved.string("client_id")?.takeIf { it.startsWith("oaiapp_") } ?: error("invalid_client")
    val refresh = old.string("refresh_token") ?: throw SessionRevoked()
    val form = FormBody.Builder().add("grant_type", "refresh_token").add("client_id", client)
        .add("refresh_token", refresh).add("resource", RESOURCE).build()
    val replacement = http.newCall(Request.Builder().url(endpoint).post(form).build()).execute().use { response ->
        val body = response.body.string()
        if (!response.isSuccessful) {
            val code = runCatching {
                val obj = Json.parseToJsonElement(body).jsonObject
                obj.string("error") ?: (obj["error"] as? JsonObject)?.string("code")
            }.getOrNull()
            if (code in setOf("invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired",
                    "refresh_token_invalidated", "refresh_token_reused")) throw SessionRevoked()
            error(if (code == "invalid_client") "invalid_client" else "refresh_http_${response.code}")
        }
        Json.parseToJsonElement(body).jsonObject
    }
    check(replacement.string("token_type").equals("Bearer", true)) { "token_type" }
    check(!replacement.string("access_token").isNullOrBlank() && !replacement.string("refresh_token").isNullOrBlank()) { "refresh_response_invalid" }
    val seconds = replacement["expires_in"]?.jsonPrimitive?.longOrNull ?: error("token_expiry_missing")
    check(seconds in 1..86400) { "token_expiry" }
    val scopes = replacement.string("scope") ?: old.string("scope") ?: error("plan_permission_missing")
    // Preserve a valid rotating replacement even when permission has been withdrawn.
    val fields = replacement.toMutableMap()
    fields["scope"] = JsonPrimitive(scopes)
    if (fields["id_token"] == null) old["id_token"]?.let { fields["id_token"] = it }
    return JsonObject(saved + mapOf("tokens" to JsonObject(fields), "expires_at" to JsonPrimitive(now + seconds),
        "plan_enabled" to JsonPrimitive(ScopeGrant(scopes).planEnabled),
        "renewal_count" to JsonPrimitive((saved["renewal_count"]?.jsonPrimitive?.longOrNull ?: 0) + 1)))
}
