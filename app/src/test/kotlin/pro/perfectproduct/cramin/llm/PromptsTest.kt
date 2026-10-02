package pro.perfectproduct.cramin.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.perfectproduct.cramin.util.Hashing

/**
 * Промпты — дословно из SPEC §6.4–§6.8 (SHA-256 блоков спецификации). Если тест упал, значит
 * промпт разошёлся со спецификацией: сначала правьте `docs/SPEC.md`, потом хэш.
 */
class PromptsTest {
    private val expected = mapOf(
        "brief" to "d0a9a2b3098f9ed469f07a044d7ce5a6e36d83d7aad7157591f392e0405aaee0",
        "translate" to "be8427039874e354fd4cb9552424b57aa71f4fbe0762f290afc8f9adc5f99f77",
        "extract" to "f9e5a3104f05148b2efa7018307746c5c204ae78aa51503f9fc1db225e258eb1",
        "consolidate" to "54e2286fbe81f47f4e224879db44a5338bd91d88eb988d73101fbad2f7ac7e39",
    )

    @Test
    fun promptsMatchSpecVerbatim() {
        val actual = mapOf(
            "brief" to Prompts.BRIEF,
            "translate" to Prompts.TRANSLATE,
            "extract" to Prompts.EXTRACT,
            "consolidate" to Prompts.CONSOLIDATE,
        )
        for ((name, text) in actual) {
            assertEquals("prompt «$name» отличается от SPEC", expected.getValue(name), Hashing.sha256Hex(text.toByteArray(Charsets.UTF_8)))
        }
    }

    @Test
    fun promptsContainKeyInstructions() {
        assertTrue(Prompts.TRANSLATE.contains("merge up to 3 adjacent sentences"))
        assertTrue(Prompts.EXTRACT.contains("Do not retranslate the text."))
        assertTrue(Prompts.CONSOLIDATE.contains("Every occurrence id must appear exactly once."))
        assertTrue(Prompts.BRIEF.contains("up to 80 recurring"))
    }

    @Test
    fun schemasParseAndAreStrict() {
        for (s in listOf(Schemas.BRIEF, Schemas.TRANSLATE, Schemas.EXTRACT, Schemas.CONSOLIDATE)) {
            assertEquals("false", s["additionalProperties"].toString())
            assertTrue(s.containsKey("required"))
        }
    }
}
