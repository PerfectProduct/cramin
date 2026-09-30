package pro.perfectproduct.cramin.llm

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ModelConfigTest {
    private fun cfg(vararg roles: Pair<String, String>, schema: Int = 1, maxSection: Int? = null) = ModelsConfigFile(
        schemaVersion = schema,
        roles = roles.associate { (r, m) -> r to RoleConfig(m, temperature = 0.2, maxTokens = 1000) },
        pipeline = PipelineParamsJson(translateMaxSectionWords = maxSection),
    )

    private fun catalog(vararg models: Pair<String, Boolean>) = object : CatalogView {
        override fun find(modelId: String): CatalogModel? = models.firstOrNull { it.first == modelId }?.let { (id, so) ->
            CatalogModel(id, id, 128_000, 8192, 1e-7, 4e-7, if (so) listOf("structured_outputs", "response_format") else listOf("temperature"), listOf("text"), listOf("text"))
        }
    }

    private val embedded = cfg("brief" to "e/brief", "translate" to "e/translate", "extract" to "e/extract", "consolidate" to "e/consolidate", "stt" to "e/stt", maxSection = 4000)

    @Test
    fun sourcePriorityOverrideRemoteEmbedded() {
        val remote = cfg("brief" to "r/brief", "translate" to "r/translate", "extract" to "r/extract", "consolidate" to "r/consolidate", "stt" to "r/stt", maxSection = 3000)
        val overrides = ModelOverrides(mapOf("translate" to RoleOverride(model = "o/translate"), "extract" to RoleOverride(temperature = 0.9)))
        val eff = ModelConfigResolver.resolve(overrides, remote, embedded, catalog = null)
        assertEquals("o/translate", eff.role(ModelRole.TRANSLATE).model)
        assertEquals(ConfigSource.OVERRIDE, eff.role(ModelRole.TRANSLATE).source)
        assertEquals(0.2, eff.role(ModelRole.TRANSLATE).temperature) // параметр из следующего источника
        assertEquals("r/extract", eff.role(ModelRole.EXTRACT).model)
        assertEquals(0.9, eff.role(ModelRole.EXTRACT).temperature) // отдельный параметр переопределён
        assertEquals(ConfigSource.REMOTE, eff.role(ModelRole.BRIEF).source)
        assertEquals(3000, eff.pipeline.translateMaxSectionWords)
        assertTrue(eff.warnings.isEmpty())
    }

    @Test
    fun unknownSchemaVersionIsIgnored() {
        assertNull(ModelsConfigFile.parseOrNull("""{"schemaVersion": 2, "roles": {}}"""))
        assertNull(ModelsConfigFile.parseOrNull("not json"))
        val eff = ModelConfigResolver.resolve(null, ModelsConfigFile.parseOrNull("""{"schemaVersion": 2}"""), embedded, null)
        assertEquals(ConfigSource.EMBEDDED, eff.role(ModelRole.BRIEF).source)
        assertEquals(4000, eff.pipeline.translateMaxSectionWords)
    }

    @Test
    fun modelWithoutStructuredOutputsFallsThroughToNextSource() {
        val remote = cfg("brief" to "r/brief-nso", "translate" to "r/translate", "extract" to "r/missing", "consolidate" to "r/consolidate", "stt" to "r/stt")
        val cat = catalog("r/brief-nso" to false, "e/brief" to true, "r/translate" to true, "e/extract" to true, "r/consolidate" to true, "e/consolidate" to true)
        val eff = ModelConfigResolver.resolve(null, remote, embedded, cat)
        assertEquals("e/brief", eff.role(ModelRole.BRIEF).model)
        assertEquals("e/extract", eff.role(ModelRole.EXTRACT).model) // модели нет в каталоге
        assertEquals("r/translate", eff.role(ModelRole.TRANSLATE).model)
        assertEquals("r/stt", eff.role(ModelRole.STT).model) // STT не проверяется на structured outputs
        assertEquals(2, eff.warnings.size)
        assertTrue(eff.warnings[0].contains("structured outputs"))
    }

    @Test
    fun embeddedAssetMatchesRepositoryFile() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val asset = context.assets.open("models.json").use { it.readBytes().toString(Charsets.UTF_8) }
        val repoFile = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "config/models.json") }
            .first { it.isFile }
        assertEquals(repoFile.readText(), asset)
        val parsed = ModelsConfigFile.parseOrNull(asset)
        assertTrue(parsed != null)
        assertEquals(ModelRole.entries.map { it.key }.toSet(), parsed?.roles?.keys)
    }
}
