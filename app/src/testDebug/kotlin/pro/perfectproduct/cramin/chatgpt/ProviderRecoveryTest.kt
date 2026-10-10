package pro.perfectproduct.cramin.chatgpt

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import org.junit.Assert.*
import pro.perfectproduct.cramin.llm.*

class ProviderRecoveryTest {
    private class Store : CredentialStore {
        var value=buildJsonObject {
            put("client_id","oaiapp_synthetic");put("plan_enabled",true);put("expires_at",System.currentTimeMillis()/1000+3600)
            putJsonObject("tokens") { put("access_token","old");put("refresh_token","refresh-old");put("scope",REQUESTED_SCOPES.joinToString(" ")) }
        }
        override fun read()=synchronized(this) { value }
        override fun update(block:(JsonObject)->JsonObject)=synchronized(this) { block(value).also { value=it } }
    }
    private fun request()=LlmRequest(ModelRole.TRANSLATE,"synthetic-slug","synthetic instructions","synthetic input",
        Schemas.TRANSLATE_NAME,Schemas.TRANSLATE,null,null,provider=TextProvider.CHATGPT_PLAN)
    private val noFallback=object:LlmClient { override suspend fun complete(request:LlmRequest):LlmResponse=error("paid_fallback_forbidden") }

    @Test fun explicit401RefreshesOnceAndResendsWithLatestSavedGrant() = runBlocking {
        var refreshes=0;var requests=0
        val store=Store()
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            val model=chain.request().url.encodedPath.endsWith("models")
            val body=if(model) """{"models":[{"slug":"synthetic-slug","display_name":"Synthetic","visibility":"list"}]}"""
                else { refreshes++; """{"access_token":"new","refresh_token":"refresh-new","token_type":"Bearer","expires_in":3600}""" }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("synthetic").body(body.toResponseBody()).build()
        }.build()
        val manager=ChatGptSessionManager(store,http)
        val client=ProviderLlmClient(noFallback,manager) { session, _ -> object:LlmClient {
            override suspend fun complete(request:LlmRequest):LlmResponse {
                val active=session();requests++
                if(requests==1) { assertEquals("old",active.accessToken);throw ResponseFailure(ResponseFailureKind.HTTP,httpStatus=401) }
                assertEquals("new",active.accessToken)
                return LlmResponse("{}","stop",LlmUsage.ZERO,request.model)
            }
        } }
        client.complete(request())
        assertEquals(1,refreshes);assertEquals(2,requests)
        assertEquals("refresh-new",store.value.getValue("tokens").jsonObject.string("refresh_token"))
    }
    @Test fun limitAndInterruptedInferenceNeverAutomaticallyRetryOrFallback() = runBlocking {
        val http=OkHttpClient.Builder().addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
            .body("""{"models":[{"slug":"synthetic-slug","display_name":"Synthetic","visibility":"list"}]}""".toResponseBody()).build() }.build()
        for (failure in listOf(ResponseFailure(ResponseFailureKind.USAGE_LIMIT,httpStatus=429),ResponseFailure(ResponseFailureKind.INTERRUPTED))) {
            var calls=0
            val client=ProviderLlmClient(noFallback,ChatGptSessionManager(Store(),http)) { session,_ -> object:LlmClient {
                override suspend fun complete(request:LlmRequest):LlmResponse { session();calls++;throw failure }
            } }
            try { client.complete(request());fail() } catch (e:LlmException) {
                if(failure.httpStatus==429) assertEquals(ChatGptFailure.LIMIT,(e as LlmException.ChatGpt).kind)
                else assertTrue(e is LlmException.Network)
                assertNotNull(e.diagnostic)
            }
            assertEquals(1,calls)
        }
    }
}
