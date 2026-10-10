package pro.perfectproduct.cramin.chatgpt

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class RefreshSessionTest {
    private fun saved() = buildJsonObject {
        put("host_id", "synthetic-host"); put("client_id", "oaiapp_synthetic"); put("subject", "synthetic-account")
        put("expires_at", 1); put("plan_enabled", true)
        putJsonObject("tokens") { put("access_token", "old-access"); put("refresh_token", "old-refresh")
            put("id_token", "retained-id"); put("scope", REQUESTED_SCOPES.joinToString(" ")) }
    }
    private val response = """{"access_token":"new-access","refresh_token":"rotated-refresh","expires_in":3600,"token_type":"Bearer"}"""
    @Test fun refreshUsesIssuedClientAndAtomicallyReplacesRotatingGrant() {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse.Builder().body(response).build())
            val r=refreshRecord(saved(),OkHttpClient(),100,server.url("/token").toString())
            val form=server.takeRequest().body!!.utf8()
            assertTrue(form.contains("grant_type=refresh_token")); assertTrue(form.contains("client_id=oaiapp_synthetic"))
            assertTrue(form.contains("refresh_token=old-refresh")); assertFalse(form.contains("scope="))
            assertTrue(form.contains("resource=https%3A%2F%2Fapi.openai.com%2Fv1"))
            assertEquals("rotated-refresh",r["tokens"]!!.jsonObject.string("refresh_token"))
            assertEquals("retained-id",r["tokens"]!!.jsonObject.string("id_token"))
            assertEquals(JsonPrimitive(3700),r["expires_at"]); assertEquals(saved()["host_id"],r["host_id"])
        }
    }
    @Test fun terminalRevocationIsDistinctFromTemporaryNetworkAndClientErrors() {
        MockWebServer().use { server ->
            server.start()
            for (code in listOf("invalid_grant","invalid_refresh_token","refresh_token_reused")) {
                server.enqueue(MockResponse.Builder().code(400).body("{\"error\":\"$code\"}").build())
                try { refreshRecord(saved(),OkHttpClient(),100,server.url("/token").toString()); fail() }
                catch (_: SessionRevoked) {}
            }
            server.enqueue(MockResponse.Builder().code(503).body("{}").build())
            try { refreshRecord(saved(),OkHttpClient(),100,server.url("/token").toString()); fail() }
            catch (e: IllegalStateException) { assertEquals("refresh_http_503",e.message) }
        }
    }

    private class MemoryVault(var value: JsonObject) : CredentialStore {
        override fun read() = synchronized(this) { value }
        override fun update(block: (JsonObject) -> JsonObject) = synchronized(this) {
            block(value).also { value = it }
        }
    }
    @Test fun concurrentManagersRotateOnceAndReuseAtomicReplacement() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse.Builder().body(response).build())
            val vault = MemoryVault(saved())
            val now = System.currentTimeMillis()/1000
            val managers = List(2) { ChatGptSessionManager(vault, OkHttpClient(), { now }, server.url("/token").toString()) }
            val sessions = (0 until 12).map { i -> async(Dispatchers.Default) { managers[i%2].session() } }.awaitAll()
            assertTrue(sessions.all { it.accessToken == "new-access" })
            assertEquals(1, server.requestCount)
            assertEquals("rotated-refresh", vault.value.getValue("tokens").jsonObject.string("refresh_token"))
            // A delayed 401 for the old token must not rotate the replacement again.
            assertEquals("new-access", managers[1].session("old-access").accessToken)
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun temporaryRefreshFailureRetainsGrantAndRevocationRemovesIt() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val vault = MemoryVault(saved()); val original = vault.value
            val manager = ChatGptSessionManager(vault, OkHttpClient(), { System.currentTimeMillis()/1000 }, server.url("/token").toString())
            server.enqueue(MockResponse.Builder().code(503).body("{}").build())
            try { manager.session(); fail() } catch (_: IllegalStateException) {}
            assertEquals(original, vault.value)
            server.enqueue(MockResponse.Builder().code(400).body("{\"error\":\"invalid_grant\"}").build())
            try { manager.session(); fail() } catch (e: IllegalStateException) { assertEquals("session_revoked", e.message) }
            assertNull(vault.value["tokens"]); assertEquals(original["client_id"], vault.value["client_id"])
        }
    }

    @Test fun earliestRefreshTimePreventsSendingAndRetainsCredentials() {
        MockWebServer().use { server ->
            server.start()
            val original=saved(); val tokens=original.getValue("tokens").jsonObject
            val future=JsonObject(original + ("tokens" to JsonObject(tokens + ("earliest_refresh_at" to JsonPrimitive(200)))))
            try { refreshRecord(future,OkHttpClient(),100,server.url("/token").toString()); fail() }
            catch (e: IllegalStateException) { assertEquals("refresh_too_early",e.message) }
            assertEquals(0,server.requestCount)
        }
    }
    @Test fun withdrawnPermissionStillSavesTheRotatingReplacement() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val body=JsonObject(Json.parseToJsonElement(response).jsonObject + ("scope" to JsonPrimitive("openid profile email")))
            server.enqueue(MockResponse.Builder().body(body.toString()).build())
            val vault=MemoryVault(saved())
            val manager=ChatGptSessionManager(vault,OkHttpClient(),{System.currentTimeMillis()/1000},server.url("/token").toString())
            try { manager.session(); fail() } catch (e:IllegalStateException) { assertEquals("plan_permission_missing",e.message) }
            assertEquals("rotated-refresh",vault.value.getValue("tokens").jsonObject.string("refresh_token"))
            assertEquals(JsonPrimitive(false),vault.value["plan_enabled"])
        }
    }
    @Test fun disconnectRevokesIssuedClientWithBoundedRetryThenClearsLocally() = runBlocking {
        var attempts=0
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request()
            val metadata=request.url.encodedPath.endsWith("openid-configuration")
            val code=if (metadata) 200 else { attempts++; if (attempts==1) 503 else 200 }
            if (!metadata) {
                val form=request.body as okhttp3.FormBody
                assertEquals("old-refresh",form.value(0));assertEquals("refresh_token",form.value(1));assertEquals("oaiapp_synthetic",form.value(2))
                assertNull(request.header("Authorization"))
            }
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(code).message("synthetic")
                .body((if(metadata) "{\"issuer\":\"https://auth.openai.com\",\"revocation_endpoint\":\"https://auth.openai.com/revoke\"}" else "").toResponseBody()).build()
        }.build()
        val vault=MemoryVault(saved());val manager=ChatGptSessionManager(vault,http)
        assertEquals("ChatGPT отключён.",manager.disconnect());assertEquals(2,attempts)
        assertNull(vault.value["tokens"]);assertEquals("oaiapp_synthetic",vault.value.string("client_id"))
    }
}
