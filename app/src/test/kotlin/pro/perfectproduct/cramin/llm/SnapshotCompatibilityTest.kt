package pro.perfectproduct.cramin.llm

import org.junit.Assert.*
import org.junit.Test

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
        assertEquals("old/stt", snapshot.config.role(ModelRole.STT).model)
        assertEquals(1234, snapshot.config.role(ModelRole.TRANSLATE).maxTokens)
        assertTrue(ProcessingSnapshot.decode(snapshot.encode()).legacyParametersUnknown)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnknownVersion() {
        val config = EffectiveConfig(emptyMap(), PipelineParams(), emptyList())
        ProcessingSnapshot.decode(ProcessingSnapshot(version = 999, config = config).encode())
    }
}
