package pro.perfectproduct.cramin.llm

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import pro.perfectproduct.cramin.data.db.TopicCategory
import pro.perfectproduct.cramin.data.repo.CategoryFilter

class TopicCategoriesTest {
    private val role = EffectiveRole(ModelRole.TOPIC, "fake/topic", 0.1, null, ConfigSource.EMBEDDED, buildJsonObject { put("effort", "low") })
    private val context = TopicContext("Regression", "Training models", listOf("Coefficients"))
    private val items = listOf(TopicItem("1", "train", "VERB", "fit parameters", "обучать", listOf("We train regression models.")))

    @Test fun eightIndependentCombinationsAndUnknown() {
        for (mask in 0..7) {
            assertEquals(mask == 7, CategoryFilter.accepts(null, mask))
            for (category in TopicCategory.entries) assertEquals(mask and category.bit != 0, CategoryFilter.accepts(category, mask))
        }
    }
    @Test fun wireForBothPathsRequiresSchemaAndReasoningEvenWhenCatalogOmitsThem() {
        val request = TopicCategories.request(role, context, items, null)
        for (r in listOf(request, request.copy(role = ModelRole.CONSOLIDATE, schema = TopicCategories.consolidationSchema))) {
            val body = Json.parseToJsonElement(ChatRequestBody.build(r, emptySet(), false).toString()).jsonObject
            assertEquals("fake/topic", body["model"]?.jsonPrimitive?.content)
            assertEquals("low", body["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
            assertEquals(true, body["provider"]?.jsonObject?.get("require_parameters")?.jsonPrimitive?.boolean)
            val format = body.getValue("response_format").jsonObject
            assertEquals("json_schema", format.getValue("type").jsonPrimitive.content)
            assertTrue(format.getValue("json_schema").jsonObject.getValue("strict").jsonPrimitive.boolean)
            assertTrue(format.toString().contains("\"enum\":[\"CORE\",\"RELATED\",\"GENERAL\"]"))
        }
    }
    @Test fun responsesMustBeCompleteUniqueAndFinished() {
        fun response(s: String, finish: String = "stop") = LlmResponse(s, finish, LlmUsage.ZERO, role.model)
        assertEquals(mapOf("1" to TopicCategory.CORE), TopicCategories.validate(response("""{"items":[{"id":"1","category":"CORE"}]}"""), listOf("1")))
        for (bad in listOf("""{"items":[]} """, """{"items":[{"id":"2","category":"CORE"}]}""",
            """{"items":[{"id":"1","category":"CORE"},{"id":"1","category":"GENERAL"}]}""",
            """{"items":[{"id":"1","category":"OTHER"}]}""", """{"items":[{"id":"1"}]}""",
            """{"items":[{"id":"1","category":"CORE","extra":1}]}""")) {
            assertThrows(LlmException.InvalidResponse::class.java) { TopicCategories.validate(response(bad), listOf("1")) }
        }
        for (finish in listOf("length", "content_filter", "error", "refusal")) assertThrows(LlmException.InvalidResponse::class.java) {
            TopicCategories.validate(response("""{"items":[{"id":"1","category":"CORE"}]}""", finish), listOf("1"))
        }
    }
    @Test fun serializedSizeAndExpectedResponseBoundRequests() {
        assertTrue(TopicCategories.fits(TopicCategories.request(role, context, items, null), null))
        assertFalse(TopicCategories.fits(TopicCategories.request(role, context, items.map { it.copy(examples = listOf("א".repeat(30000))) }, null), null))
        val many = (1..1000).map { items.single().copy(id = it.toString()) }
        assertFalse(TopicCategories.fits(TopicCategories.request(role, context, many, null), null))
    }
}
