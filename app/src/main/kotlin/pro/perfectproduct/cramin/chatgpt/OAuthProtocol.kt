package pro.perfectproduct.cramin.chatgpt

import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal const val ISSUER = "https://auth.openai.com"
internal const val RESOURCE = "https://api.openai.com/v1"
internal const val DYNAMIC_CLIENT = "dynamic_agent_client"
internal val IDENTITY_SCOPES = setOf("openid", "profile", "email")
internal val PLAN_SCOPES = setOf("offline_access", "resource.invoke", "chatgpt.tokens.use.direct")
internal val REQUESTED_SCOPES = IDENTITY_SCOPES + PLAN_SCOPES
internal fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun randomValue(): String = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
internal fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

/** Memory only: process death invalidates the attempt; secrets never enter savedInstanceState. */
internal class OAuthAttempt(
    val redirectUri: String,
    val clientId: String = DYNAMIC_CLIENT,
    val expectedSubject: String? = null,
    val state: String = randomValue(),
    val nonce: String = randomValue(),
    val verifier: String = randomValue(),
    val expiresAt: Long = System.currentTimeMillis() + 600_000,
) {
    private var consumed = false
    fun authorizationUrl(hostId: String, idTokenHint: String? = null): String {
        val url = "$ISSUER/api/accounts/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("ext_agent_host_id", hostId)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("scope", REQUESTED_SCOPES.joinToString(" "))
            .addQueryParameter("resource", RESOURCE)
            .addQueryParameter("state", state).addQueryParameter("nonce", nonce)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("code_challenge", challenge(verifier))
        if (clientId == DYNAMIC_CLIENT) url.addQueryParameter("agent_name_hint", "Cramin")
        else if (idTokenHint != null) url.addQueryParameter("id_token_hint", idTokenHint)
        return url.build().toString()
    }

    @Synchronized fun cancel() { consumed = true }

    /** Consume before validation/exchange, including errors: a callback can never be redeemed twice. */
    @Synchronized fun consume(callback: String, now: Long = System.currentTimeMillis()): Callback {
        check(!consumed) { "callback_replayed" }
        consumed = true
        check(now < expiresAt) { "callback_expired" }
        val url = callback.toHttpUrl()
        val expected = redirectUri.toHttpUrl()
        check(url.scheme == expected.scheme && url.host == expected.host && url.port == expected.port &&
            url.encodedPath == expected.encodedPath && url.fragment == null && url.username.isEmpty() && url.password.isEmpty()) { "callback_uri" }
        for (name in listOf("state", "code", "client_id", "error")) {
            check(url.queryParameterValues(name).size <= 1) { "callback_duplicate_parameter" }
        }
        val received = url.queryParameter("state") ?: error("callback_state")
        check(MessageDigest.isEqual(state.toByteArray(), received.toByteArray())) { "callback_state" }
        if (url.queryParameter("error") != null) return Callback.Denied
        val issued = url.queryParameter("client_id") ?: clientId
        check(issued.startsWith("oaiapp_") && issued.length > 7) { "callback_client_id" }
        check(clientId == DYNAMIC_CLIENT || clientId == issued) { "callback_client_changed" }
        val code = url.queryParameter("code")?.takeIf { it.isNotBlank() } ?: error("callback_code")
        return Callback.Code(code, issued)
    }
}

internal sealed interface Callback {
    class Code(val code: String, val clientId: String) : Callback
    data object Denied : Callback
}

internal class ScopeGrant(scope: String) {
    val scopes = scope.split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
    val identityEnabled = scopes.containsAll(IDENTITY_SCOPES)
    val planEnabled = identityEnabled && scopes.containsAll(PLAN_SCOPES)
}
