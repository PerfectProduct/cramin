package pro.perfectproduct.cramin.chatgpt

import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL

class OAuthProtocolTest {
    private val redirect = "http://127.0.0.1:1455/auth/callback"
    private fun tx(client: String = DYNAMIC_CLIENT) = OAuthAttempt(redirect, client, state = "expected", nonce = "nonce", verifier = "verifier", expiresAt = 1000)
    private fun callback(query: String) = "$redirect?$query"
    private inline fun rejects(block: () -> Unit) { try { block(); fail("accepted invalid OAuth data") } catch (_: IllegalStateException) {} }

    @Test fun wrongStateConsumesAttemptAndReplayFails() {
        val tx = tx()
        rejects { tx.consume(callback("state=wrong&code=fake&client_id=oaiapp_test"), 100) }
        rejects { tx.consume(callback("state=expected&code=fake&client_id=oaiapp_test"), 100) }
    }
    @Test fun successfulCallbackIsOneTimeAndUsesIssuedId() {
        val tx = tx()
        val result = tx.consume(callback("state=expected&code=fake&client_id=oaiapp_test"), 100) as Callback.Code
        assertEquals("oaiapp_test", result.clientId)
        rejects { tx.consume(callback("state=expected&code=fake&client_id=oaiapp_test"), 100) }
    }
    @Test fun cancellationAndExpirationRejectCallbacks() {
        val tx = tx(); tx.cancel()
        rejects { tx.consume(callback("state=expected&code=fake"), 100) }
        rejects { tx().consume(callback("state=expected&code=fake"), 1000) }
    }
    @Test fun deniedRequiresValidStateAndNeverReturnsCode() {
        assertSame(Callback.Denied, tx().consume(callback("state=expected&error=access_denied"), 100))
        rejects { tx().consume(callback("state=wrong&error=access_denied"), 100) }
    }
    @Test fun missingOrDynamicClientIdIsRejectedOnRegistration() {
        rejects { tx().consume(callback("state=expected&code=fake"), 100) }
        rejects { tx().consume(callback("state=expected&code=fake&client_id=dynamic_agent_client"), 100) }
    }
    @Test fun returningClientMustMatchButCanBeOmitted() {
        assertEquals("oaiapp_saved", (tx("oaiapp_saved").consume(callback("state=expected&code=fake"), 100) as Callback.Code).clientId)
        rejects { tx("oaiapp_saved").consume(callback("state=expected&code=fake&client_id=oaiapp_other"), 100) }
    }
    @Test fun duplicatesAndWrongUriAreRejected() {
        rejects { tx().consume(callback("state=expected&state=expected&code=fake"), 100) }
        rejects { tx().consume("http://localhost:1455/auth/callback?state=expected&code=fake", 100) }
        rejects { tx().consume("http://127.0.0.1:1455/callback?state=expected&code=fake", 100) }
    }
    @Test fun completeScopesAreNecessaryForPlanAndCallbackScopeIsNotTrusted() {
        assertTrue(ScopeGrant(REQUESTED_SCOPES.joinToString(" ")).planEnabled)
        for (missing in REQUESTED_SCOPES) assertFalse(ScopeGrant((REQUESTED_SCOPES - missing).joinToString(" ")).planEnabled)
        assertTrue(ScopeGrant(IDENTITY_SCOPES.joinToString(" ")).identityEnabled)
        assertFalse(ScopeGrant(IDENTITY_SCOPES.joinToString(" ")).planEnabled)
    }
    @Test fun authorizationUsesOfficialLoopbackHostPkceAndNonce() {
        val tx = tx()
        val url = tx.authorizationUrl("urn:uuid:synthetic").toHttpUrl()
        assertEquals("https://auth.openai.com/api/accounts/authorize", url.toString().substringBefore('?'))
        assertEquals(redirect, url.queryParameter("redirect_uri"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("nonce", url.queryParameter("nonce"))
        assertEquals("Cramin", url.queryParameter("agent_name_hint"))
        assertEquals(RESOURCE, url.queryParameter("resource"))
        assertEquals(REQUESTED_SCOPES, url.queryParameter("scope")!!.split(' ').toSet())
        assertNull(tx("oaiapp_saved").authorizationUrl("urn:uuid:synthetic").toHttpUrl().queryParameter("agent_name_hint"))
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        assertNotEquals(OAuthAttempt(redirect).state, OAuthAttempt(redirect).state)
    }

    @Test fun realLoopbackHttpRedirectValidatesSyntheticDenial() = runBlocking {
        LoopbackListener(synthetic = true).use { listener ->
            val attempt = OAuthAttempt(listener.redirectUri)
            val received = async(Dispatchers.IO) { listener.receive(attempt) }
            withContext(Dispatchers.IO) {
                val connection = URL(listener.syntheticStart).openConnection() as HttpURLConnection
                connection.connectTimeout = 5000; connection.readTimeout = 5000
                try {
                    assertEquals(200, connection.responseCode)
                    assertEquals("no-store", connection.getHeaderField("Cache-Control"))
                    assertTrue(connection.inputStream.bufferedReader().use { it.readText() }.contains("Return to Cramin"))
                } finally { connection.disconnect() }
            }
            assertSame(Callback.Denied, attempt.consume(withTimeout(5000) { received.await() }))
        }
    }

    @Test fun speculativeEmptyConnectionDoesNotTerminateListener() = runBlocking {
        LoopbackListener(synthetic = true).use { listener ->
            val attempt = OAuthAttempt(listener.redirectUri)
            val received = async(Dispatchers.IO) { listener.receive(attempt) }
            withContext(Dispatchers.IO) {
                java.net.Socket("127.0.0.1", URL(listener.redirectUri).port).close()
                val connection = URL(listener.syntheticStart).openConnection() as HttpURLConnection
                connection.connectTimeout = 5000; connection.readTimeout = 5000
                try { assertEquals(200, connection.responseCode); connection.inputStream.close() }
                finally { connection.disconnect() }
            }
            assertSame(Callback.Denied, attempt.consume(withTimeout(5000) { received.await() }))
        }
    }

    private val pair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun b64(text: String) = b64(text.toByteArray())
    private fun keys(): JsonObject {
        val key = pair.public as RSAPublicKey
        return buildJsonObject { putJsonArray("keys") { addJsonObject {
            put("kid", "test"); put("kty", "RSA"); put("alg", "RS256"); put("use", "sig")
            put("n", b64(key.modulus.toByteArray())); put("e", b64(key.publicExponent.toByteArray()))
        } } }
    }
    private fun claims() = buildJsonObject {
        put("iss", ISSUER); put("aud", "oaiapp_test"); put("sub", "synthetic-subject")
        put("nonce", "nonce"); put("iat", 90); put("exp", 200)
    }
    private fun token(claims: JsonObject, algorithm: String = "RS256"): String {
        val content = b64("""{"alg":"$algorithm","kid":"test"}""") + "." + b64(claims.toString())
        return content + "." + b64(Signature.getInstance("SHA256withRSA").run {
            initSign(pair.private); update(content.toByteArray()); sign()
        })
    }
    @Test fun idTokenVerifiesSignatureAndClaims() {
        assertEquals("synthetic-subject", IdTokenVerifier.verify(token(claims()), keys(), "oaiapp_test", "nonce", 100).string("sub"))
    }
    @Test fun invalidIssuerAudienceNonceExpiryAndFutureIssuedTimeAreRejected() {
        val changes = mapOf("iss" to JsonPrimitive("https://other.invalid"), "aud" to JsonPrimitive("other"),
            "nonce" to JsonPrimitive("other"), "exp" to JsonPrimitive(80), "iat" to JsonPrimitive(120), "nbf" to JsonPrimitive(120), "sub" to JsonPrimitive(""))
        for ((key, value) in changes) rejects { IdTokenVerifier.verify(token(JsonObject(claims() + (key to value))), keys(), "oaiapp_test", "nonce", 100) }
    }
    @Test fun tamperingUnknownKidAndAlgorithmConfusionAreRejected() {
        val good = token(claims())
        val parts = good.split('.')
        rejects { IdTokenVerifier.verify(parts[0] + "." + b64(JsonObject(claims() + ("sub" to JsonPrimitive("tampered"))).toString()) + "." + parts[2], keys(), "oaiapp_test", "nonce", 100) }
        rejects { IdTokenVerifier.verify(good, buildJsonObject { putJsonArray("keys") {} }, "oaiapp_test", "nonce", 100) }
        rejects { IdTokenVerifier.verify(token(claims(), "none"), keys(), "oaiapp_test", "nonce", 100) }
    }
}
