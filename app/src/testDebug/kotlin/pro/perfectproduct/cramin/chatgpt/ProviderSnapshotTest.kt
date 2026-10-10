package pro.perfectproduct.cramin.chatgpt

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import pro.perfectproduct.cramin.llm.*
import pro.perfectproduct.cramin.pipeline.*
import pro.perfectproduct.cramin.util.Lang
import java.io.File

class ProviderSnapshotTest {
    private val embedded get() = ModelsConfigFile.parse(File("../config/models.json").readText())
    @Test fun planSnapshotPinsSlugAllTextRolesAndSupportedParametersWithoutCredentials() {
        val config = ChatGptConfig.resolve("account-slug", embedded)
        val snapshot = ProcessingSnapshot.capture(config, null)
        val saved = snapshot.encode()
        assertEquals(snapshot, ProcessingSnapshot.decode(saved))
        assertEquals(TextProvider.CHATGPT_PLAN, snapshot.provider)
        assertEquals(350, config.pipeline.extractChunkWords)
        assertTrue(config.roles.values.filter { it.role.isText }.all {
            it.model == "account-slug" && it.provider == TextProvider.CHATGPT_PLAN && it.temperature == null && it.maxTokens == null && it.reasoning == null
        })
        assertEquals(TextProvider.OPENROUTER, config.role(ModelRole.STT).provider)
        assertFalse(saved.contains("access_token")); assertFalse(saved.contains("refresh_token"))
        val request = BriefRequestFactory.build(listOf("Water freezes at zero degrees Celsius."), Lang.EN, Lang.RU, config, null)
        assertEquals(TextProvider.CHATGPT_PLAN, request.provider)
        val body = ChatRequestBody.forSizing(request, null, true)
        assertEquals(setOf("model", "instructions", "input", "store", "stream", "text"), body.keys)
        assertEquals(JsonPrimitive(false), body["store"]); assertEquals(JsonPrimitive(true), body["stream"])
        assertFalse(body.toString().contains("max_output_tokens"))
    }
    @Test fun oldSnapshotsDefaultToOpenRouterAndDoNotAcquirePlanSettings() {
        val config = ModelConfigResolver.resolve(null,null,embedded,null)
        val root = Json.parseToJsonElement(ProcessingSnapshot.capture(config,null).encode()).jsonObject
        val oldConfig = root.getValue("config").jsonObject
        val roles = oldConfig.getValue("roles").jsonObject.mapValues { (_,v) -> JsonObject(v.jsonObject - "provider") }
        val old = JsonObject(root - "provider" + ("config" to JsonObject(oldConfig - "provider" + ("roles" to JsonObject(roles)))))
        val restored = ProcessingSnapshot.decode(old.toString())
        assertEquals(TextProvider.OPENROUTER, restored.provider)
        assertTrue(restored.config.roles.values.all { it.provider == TextProvider.OPENROUTER })
        val request = BriefRequestFactory.build(listOf("A synthetic sentence."),Lang.EN,Lang.RU,restored.config,null)
        assertEquals(TextProvider.OPENROUTER, request.provider)
        assertTrue(ChatRequestBody.forSizing(request,null,true).containsKey("messages"))
    }
    @Test fun everyPlanSchemaUsedByPipelinePassesStrictLocalContract() {
        for (schema in listOf(Schemas.BRIEF,Schemas.TRANSLATE,Schemas.EXTRACT,Schemas.CONSOLIDATE,TopicCategories.schema,TopicCategories.consolidationSchema))
            PrototypeSchema.checkSchema(schema)
    }
    @Test fun routerUsesRequestProviderEvenAfterSettingsChange() = runBlocking {
        var calls = 0
        val delegate = object : LlmClient {
            override suspend fun complete(request: LlmRequest): LlmResponse { calls++; return LlmResponse("{}","stop",LlmUsage.ZERO,request.model) }
        }
        val noSession = object : CredentialStore {
            override fun read() = buildJsonObject {}
            override fun update(block: (JsonObject) -> JsonObject): JsonObject = error("must_not_touch_oauth")
        }
        val router = ProviderLlmClient(delegate,ChatGptSessionManager(noSession,okhttp3.OkHttpClient()))
        val r = BriefRequestFactory.build(listOf("A synthetic sentence."),Lang.EN,Lang.RU,ModelConfigResolver.resolve(null,null,embedded,null),null)
        router.complete(r)
        assertEquals(1,calls)
    }
}
