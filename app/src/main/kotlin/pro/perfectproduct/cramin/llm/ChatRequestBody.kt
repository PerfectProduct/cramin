package pro.perfectproduct.cramin.llm

import kotlinx.serialization.json.*

/** Single serializer shared by transport and conservative BRIEF sizing. */
object ChatRequestBody {
    fun build(r: LlmRequest, supported: Set<String>?, requireParameters: Boolean): JsonObject = buildJsonObject {
        put("model", r.model)
        put(
            "messages",
            buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", r.system) })
                add(buildJsonObject { put("role", "user"); put("content", r.user) })
            },
        )
        put(
            "response_format",
            buildJsonObject {
                put("type", "json_schema")
                put(
                    "json_schema",
                    buildJsonObject {
                        put("name", r.schemaName)
                        put("strict", true)
                        put("schema", r.schema)
                    },
                )
            },
        )
        put("usage", buildJsonObject { put("include", true) })
        if (r.temperature != null && (supported == null || "temperature" in supported)) put("temperature", r.temperature)
        r.maxTokens?.let { put("max_tokens", it) }
        if (r.reasoning != null && (r.strictTopic || supported == null || "reasoning" in supported)) put("reasoning", r.reasoning)
        if (requireParameters || r.strictTopic) {
            // Роутинг только к провайдерам, которые честно поддерживают structured outputs (CRM-DL-011).
            put("provider", buildJsonObject { put("require_parameters", true) })
        }
    }

}
