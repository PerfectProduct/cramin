package pro.perfectproduct.cramin.chatgpt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import pro.perfectproduct.cramin.util.useCancellable
import java.util.concurrent.TimeUnit

/** A dedicated client: no app interceptors, logs, redirects or automatic retry of one-time grants. */
internal fun prototypeHttp(): OkHttpClient = OkHttpClient.Builder().followRedirects(false)
    .followSslRedirects(false).retryOnConnectionFailure(false)
    .addNetworkInterceptor { chain ->
        val response = chain.proceed(chain.request())
        // Disable OkHttp's separate HTTP 503 follow-up, including for catalog GETs.
        if (response.code == 503) response.newBuilder().header("Retry-After", "2147483647").build() else response
    }.connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build()

internal class OAuthExchange(private val http: OkHttpClient = prototypeHttp()) {
    suspend fun exchange(attempt: OAuthAttempt, callback: Callback.Code): JsonObject = withContext(Dispatchers.IO) {
        // Discovery is public metadata. Never trust endpoint locations from a token or callback.
        val config = getJson("$ISSUER/.well-known/openid-configuration")
        check(config.string("issuer") == ISSUER &&
            config.string("authorization_endpoint") == "$ISSUER/api/accounts/authorize" &&
            config.string("token_endpoint") == "$ISSUER/api/accounts/oauth/token" &&
            config.string("jwks_uri") == "$ISSUER/.well-known/jwks.json") { "oidc_configuration_changed" }
        val body = FormBody.Builder().add("grant_type", "authorization_code")
            .add("client_id", callback.clientId).add("code", callback.code)
            .add("code_verifier", attempt.verifier).add("redirect_uri", attempt.redirectUri)
            .add("resource", RESOURCE).build()
        val tokens = http.newCall(Request.Builder().url("$ISSUER/api/accounts/oauth/token").post(body).build())
            .useCancellable { response ->
                // Never include response bodies in exceptions: token responses contain secrets.
                check(response.isSuccessful) { "token_exchange_http_${response.code}" }
                Json.parseToJsonElement(response.body.string()).jsonObject
            }
        val claims = IdTokenVerifier.verify(tokens.string("id_token") ?: error("id_token_missing"),
            getJson(config.string("jwks_uri")!!), callback.clientId, attempt.nonce)
        check(attempt.expectedSubject == null || attempt.expectedSubject == claims.string("sub")) { "account_changed" }
        val grant = ScopeGrant(tokens.string("scope") ?: "")
        check(grant.identityEnabled) { "identity_scopes_missing" }
        check(tokens.string("token_type").equals("Bearer", true)) { "token_type" }
        check(!tokens.string("access_token").isNullOrBlank()) { "access_token_missing" }
        if ("offline_access" in grant.scopes) check(!tokens.string("refresh_token").isNullOrBlank()) { "refresh_token_missing" }
        val expires = tokens["expires_in"]?.jsonPrimitive?.longOrNull ?: error("token_expiry_missing")
        check(expires in 1..86400) { "token_expiry" }
        buildJsonObject {
            put("client_id", callback.clientId); put("issuer", ISSUER); put("subject", claims.string("sub"))
            claims.string("email")?.let { put("email", it) }
            put("tokens", tokens); put("expires_at", System.currentTimeMillis() / 1000 + expires)
            put("plan_enabled", grant.planEnabled)
        }
    }
    private suspend fun getJson(url: String): JsonObject = http.newCall(Request.Builder().url(url).build()).useCancellable {
        check(it.isSuccessful) { "oidc_metadata_http_${it.code}" }
        Json.parseToJsonElement(it.body.string()).jsonObject
    }
}
