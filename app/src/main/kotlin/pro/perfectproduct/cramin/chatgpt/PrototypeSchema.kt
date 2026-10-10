package pro.perfectproduct.cramin.chatgpt

import kotlinx.serialization.json.*

/** The subset used by Cramin's current schemas. Unknown keywords fail closed, never silently ignored. */
internal object PrototypeSchema {
    private val keywords = setOf("type", "properties", "required", "additionalProperties", "items", "enum", "minItems", "maxItems")
    fun checkSchema(schema: JsonObject) {
        require(schema.keys.all { it in keywords }) { "unsupported_schema_keyword" }
        val types = when (val type = schema["type"]) {
            is JsonArray -> type.map { it.jsonPrimitive.content }
            is JsonPrimitive -> listOf(type.content)
            else -> error("schema_type_missing")
        }
        require(types.isNotEmpty() && types.all { it in setOf("object", "array", "string", "integer", "number", "boolean", "null") })
        (schema["properties"] as? JsonObject)?.values?.forEach { checkSchema(it.jsonObject) }
        (schema["items"] as? JsonObject)?.let(::checkSchema)
    }
    fun validate(value: JsonElement, schema: JsonObject) {
        val types = when (val t = schema.getValue("type")) {
            is JsonArray -> t.map { it.jsonPrimitive.content }
            else -> listOf(t.jsonPrimitive.content)
        }
        val validType = types.any { type -> when (type) {
            "null" -> value == JsonNull
            "object" -> value is JsonObject
            "array" -> value is JsonArray
            "string" -> value is JsonPrimitive && value.isString
            "integer" -> value is JsonPrimitive && !value.isString && value.longOrNull != null
            "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull?.isFinite() == true
            "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
            else -> false
        } }
        require(validType) { "schema_type" }
        (schema["enum"] as? JsonArray)?.let { require(value in it) { "schema_enum" } }
        if (value is JsonObject) {
            val props = (schema["properties"] as? JsonObject) ?: buildJsonObject {}
            (schema["required"] as? JsonArray)?.forEach { require(it.jsonPrimitive.content in value) { "schema_required" } }
            if (schema["additionalProperties"] == JsonPrimitive(false)) require(value.keys.all { it in props }) { "schema_extra_property" }
            for ((key, child) in value) props[key]?.let { validate(child, it.jsonObject) }
        }
        if (value is JsonArray) {
            schema["minItems"]?.jsonPrimitive?.intOrNull?.let { require(value.size >= it) { "schema_min_items" } }
            schema["maxItems"]?.jsonPrimitive?.intOrNull?.let { require(value.size <= it) { "schema_max_items" } }
            schema["items"]?.jsonObject?.let { child -> value.forEach { validate(it, child) } }
        }
    }
}
