package pro.perfectproduct.cramin.testing

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import pro.perfectproduct.cramin.llm.LlmRequest
import pro.perfectproduct.cramin.llm.ModelRole
import pro.perfectproduct.cramin.llm.Schemas

/**
 * MockWebServer, который отвечает как OpenRouter, а содержимое берёт у [FakeLlmClient]:
 * проверяет весь HTTP-путь (заголовки, structured outputs, usage) без сети и без ключа.
 */
class FakeOpenRouterServer(private val fake: FakeLlmClient = FakeLlmClient()) : AutoCloseable {
    val server = MockWebServer()
    val requests = ArrayList<RecordedRequest>()

    /** Очередь подменённых ответов на следующие вызовы chat/completions (для ретраев и ошибок). */
    val scripted = ArrayDeque<MockResponse>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(requests) { requests += request }
                val path = request.url.encodedPath
                return when {
                    path.endsWith("/chat/completions") -> synchronized(scripted) { scripted.removeFirstOrNull() } ?: chat(request)
                    path.endsWith("/models") -> MockResponse(body = CATALOG_JSON)
                    path.endsWith("/key") -> if (request.headers["Authorization"] == "Bearer $VALID_KEY") MockResponse(body = KEY_JSON) else MockResponse(code = 401, body = """{"error":{"code":401,"message":"User not found."}}""")
                    else -> MockResponse(code = 404)
                }
            }
        }
        server.start()
    }

    val baseUrl: String get() = server.url("/api/v1").toString().trimEnd('/')

    private fun chat(request: RecordedRequest): MockResponse {
        val body = Json.parseToJsonElement(request.body?.utf8().orEmpty()).jsonObject
        val model = body["model"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val messages = body["messages"]?.jsonArray.orEmpty()
        val system = messages.getOrNull(0)?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
        val user = messages.getOrNull(1)?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
        val schemaName = body["response_format"]?.jsonObject?.get("json_schema")?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
        val role = when (schemaName) {
            Schemas.BRIEF_NAME -> ModelRole.BRIEF
            Schemas.TRANSLATE_NAME -> ModelRole.TRANSLATE
            Schemas.EXTRACT_NAME -> ModelRole.EXTRACT
            Schemas.CONSOLIDATE_NAME -> ModelRole.CONSOLIDATE
            else -> return MockResponse(code = 400, body = """{"error":{"code":400,"message":"unknown schema"}}""")
        }
        val response = runBlocking {
            fake.complete(LlmRequest(role, model, system, user, schemaName, Schemas.BRIEF, body["temperature"]?.jsonPrimitive?.doubleOrNull, body["max_tokens"]?.jsonPrimitive?.intOrNull))
        }
        val json = buildJsonObject {
            put("id", "gen-fake")
            put("model", model)
            put(
                "choices",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("finish_reason", response.finishReason)
                            put("message", buildJsonObject { put("role", "assistant"); put("content", response.content) })
                        },
                    )
                },
            )
            put(
                "usage",
                buildJsonObject {
                    put("prompt_tokens", response.usage.promptTokens)
                    put("completion_tokens", response.usage.completionTokens)
                    put("cost", response.usage.costUsd ?: 0.0)
                },
            )
        }
        return MockResponse(body = json.toString(), headers = okhttp3.Headers.headersOf("Content-Type", "application/json"))
    }

    override fun close() = server.close()

    companion object {
        const val VALID_KEY = "sk-or-v1-FAKEFAKEFAKE0000000000000000000000000000000000000000000000000000"
        val CATALOG_JSON = """
            {"data":[
              {"id":"fake/brief","name":"Fake Brief","context_length":128000,"pricing":{"prompt":"0.0000001","completion":"0.0000004"},
               "top_provider":{"max_completion_tokens":16000},"supported_parameters":["temperature","response_format","structured_outputs"],
               "architecture":{"input_modalities":["text"],"output_modalities":["text"]}},
              {"id":"fake/translate","name":"Fake Translate","context_length":128000,"pricing":{"prompt":"0.000001","completion":"0.000004"},
               "top_provider":{"max_completion_tokens":8000},"supported_parameters":["temperature","structured_outputs"],
               "architecture":{"input_modalities":["text"],"output_modalities":["text"]}},
              {"id":"fake/extract","name":"Fake Extract","context_length":128000,"pricing":{"prompt":"0.0000001","completion":"0.0000004"},
               "top_provider":{"max_completion_tokens":16000},"supported_parameters":["temperature","response_format"],
               "architecture":{"input_modalities":["text"],"output_modalities":["text"]}},
              {"id":"fake/consolidate","name":"Fake Consolidate","context_length":128000,"pricing":{"prompt":"0.0000001","completion":"0.0000004"},
               "top_provider":{"max_completion_tokens":16000},"supported_parameters":["temperature","response_format"],
               "architecture":{"input_modalities":["text"],"output_modalities":["text"]}},
              {"id":"fake/no-structured","name":"No SO","context_length":8000,"pricing":{"prompt":"0","completion":"0"},
               "top_provider":{"max_completion_tokens":4000},"supported_parameters":["temperature"],
               "architecture":{"input_modalities":["text"],"output_modalities":["text"]}}
            ]}
        """.trimIndent()
        const val KEY_JSON = """{"data":{"label":"sk-or-v1-abc...xyz","usage":0.25,"limit":10.0,"limit_remaining":9.75,"is_free_tier":false}}"""
    }
}
