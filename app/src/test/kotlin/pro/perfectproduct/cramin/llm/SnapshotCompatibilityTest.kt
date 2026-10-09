package pro.perfectproduct.cramin.llm

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

class SnapshotCompatibilityTest {
    @Test fun preservesPipelineRolesAndCatalogLimits() {
        val config = ModelConfigResolver.resolve(null, null, ModelsConfigFile(1, roles = mapOf("stt" to RoleConfig("fake/stt"), "translate" to RoleConfig("fake/text", 0.3, 100))), null)
        val snapshot = ProcessingSnapshot.capture(config.copy(pipeline = config.pipeline.copy(extractChunkWords = 77)), null)
        assertEquals(snapshot, ProcessingSnapshot.decode(snapshot.encode()))
        assertEquals(77, ProcessingSnapshot.decode(snapshot.encode()).config.pipeline.extractChunkWords)
    }
    @Test fun legacyRolesRemainOriginalAndMissingParametersAreExplicit() {
        val snapshot = ProcessingSnapshot.decode("""{"translate":{"model":"old/model","temperature":0.4,"maxTokens":1234},"stt":{"model":"old/stt"}}""")
        assertTrue(snapshot.legacyParametersUnknown)
        assertEquals(700, snapshot.config.pipeline.extractChunkWords)
        assertEquals("old/stt", snapshot.config.role(ModelRole.STT).model)
        assertEquals(1234, snapshot.config.role(ModelRole.TRANSLATE).maxTokens)
        assertTrue(ProcessingSnapshot.decode(snapshot.encode()).legacyParametersUnknown)
    }
    @Test fun explicit700And350SnapshotsRoundTripWithoutChangingTheirPlans() {
        for (words in listOf(700, 350)) {
            val saved = ProcessingSnapshot(config = EffectiveConfig(emptyMap(), PipelineParams(extractChunkWords = words), emptyList())).encode()
            assertEquals(words, ProcessingSnapshot.decode(saved).config.pipeline.extractChunkWords)
            assertEquals(saved, ProcessingSnapshot.decode(saved).encode())
        }
    }
    @Test fun omittedHistoricalChunkSizeKeepsTheOld700Fallback() {
        val saved = ProcessingSnapshot(config = EffectiveConfig(emptyMap(), PipelineParams(), emptyList())).encode()
        val root = Json.parseToJsonElement(saved).jsonObject
        val config = root.getValue("config").jsonObject
        val pipeline = config.getValue("pipeline").jsonObject
        val historical = JsonObject(root + ("config" to JsonObject(config +
            ("pipeline" to JsonObject(pipeline - "extractChunkWords"))))).toString()
        val restored = ProcessingSnapshot.decode(historical)
        assertEquals(700, restored.config.pipeline.extractChunkWords)
        assertEquals(700, ProcessingSnapshot.decode(restored.encode()).config.pipeline.extractChunkWords)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnknownVersion() {
        val config = EffectiveConfig(emptyMap(), PipelineParams(), emptyList())
        ProcessingSnapshot.decode(ProcessingSnapshot(version = 999, config = config).encode())
    }
}
