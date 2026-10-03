package pro.perfectproduct.cramin.pipeline

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import mockwebserver3.MockResponse
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import pro.perfectproduct.cramin.data.repo.NewDocument
import pro.perfectproduct.cramin.llm.*
import pro.perfectproduct.cramin.testing.*
import pro.perfectproduct.cramin.util.Lang

@RunWith(RobolectricTestRunner::class)
class LegacyBriefWireTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun legacyUsesCapabilitiesOfSameModelAndPersistsThemBeforeHttp() = runTest {
        val catalog = CatalogSnapshot(TestPipeline.FAKE_CONFIG.roles.values.map {
            CatalogModel(it.model, it.model, 128000, 16000, null, null,
                listOf("max_tokens", "reasoning", "structured_outputs"), listOf("text"), listOf("text"))
        }, 0)
        FakeOpenRouterServer().use { server ->
            server.scripted += MockResponse(code = 400, body = "{}")
            val client = OpenRouterClient(OkHttpClient(), { FakeOpenRouterServer.VALID_KEY }, server.baseUrl)
            TestPipeline(tmp.newFolder(), client, catalog = catalog).use { p ->
                val id = p.documents.create(NewDocument.Text(Fixtures.text(Lang.EN), null, Lang.RU, Lang.EN))
                p.db.documentDao().setPipelineSnapshot(id, Json.encodeToString(p.config.roles), 1, 0)
                assertTrue(p.processor().process(id) is ProcessOutcome.Failed)
                val body = Json.parseToJsonElement(server.requests.single().body!!.utf8()).jsonObject
                assertEquals("fake/brief", body["model"]!!.jsonPrimitive.content)
                assertEquals("6000", body["max_tokens"].toString())
                assertFalse("Catalog says temperature unsupported", "temperature" in body)
                val saved = ProcessingSnapshot.decode(p.documents.get(id)!!.modelsSnapshotJson!!)
                assertNotNull(saved.find("fake/brief"))
                assertEquals(0.2, saved.config.role(ModelRole.BRIEF).temperature)
                val diagnostic = requireNotNull(FailureDiagnostic.forDocument(p.documents.get(id)!!))
                assertEquals(ConfigOrigin.LEGACY_FALLBACK, diagnostic.request!!.configOrigin)
                assertEquals("NOT_SENT", diagnostic.request.temperature)
                val beforeRetry = diagnostic.encode()
                p.documents.requeue(id)
                assertEquals(beforeRetry, FailureDiagnostic.forDocument(p.documents.get(id)!!)!!.encode())
            }
        }
    }
    @Test fun longRussianBriefReservesSchemaPromptAndOutputBudgetOnWire() = runTest {
        val catalog = CatalogSnapshot(TestPipeline.FAKE_CONFIG.roles.values.map {
            CatalogModel(it.model, it.model, 16000, 8000, null, null,
                listOf("max_tokens", "reasoning", "structured_outputs"), listOf("text"), listOf("text"))
        }, 0)
        FakeOpenRouterServer().use { server ->
            server.scripted += MockResponse(code = 400, body = "{}")
            TestPipeline(tmp.newFolder(), OpenRouterClient(OkHttpClient(), { FakeOpenRouterServer.VALID_KEY }, server.baseUrl), catalog = catalog).use { p ->
                val id = p.documents.create(NewDocument.Text("Достопримечательности расположены поблизости. ".repeat(1000), null, Lang.EN, Lang.RU))
                assertTrue(p.processor().process(id) is ProcessOutcome.Failed)
                val wire = server.requests.single().body!!.utf8()
                println("LONG_RU serializedUtf8Bytes=${wire.toByteArray().size}; outputReserve=6000; context=16000; protocolMargin=1024")
                assertTrue("Conservative full serialized input plus output reserve must fit", wire.toByteArray().size + 6000 <= 16000)
                val body = Json.parseToJsonElement(wire).jsonObject
                assertTrue(body["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonPrimitive.content.contains("Достопримечательности"))
            }
        }
    }
    @Test fun missingLegacyCapabilitiesNeverTriggersPaidGuess() = runTest {
        FakeOpenRouterServer().use { server ->
            TestPipeline(tmp.newFolder(), OpenRouterClient(OkHttpClient(), { FakeOpenRouterServer.VALID_KEY }, server.baseUrl)).use { p ->
                val id = p.documents.create(NewDocument.Text(Fixtures.text(Lang.EN), null, Lang.RU, Lang.EN))
                p.db.documentDao().setPipelineSnapshot(id, Json.encodeToString(p.config.roles), 1, 0)
                assertTrue(p.processor().process(id) is ProcessOutcome.Failed)
                assertTrue(server.requests.isEmpty())
                assertEquals(RequestRejection.CAPABILITIES_UNKNOWN,
                    FailureDiagnostic.forDocument(p.documents.get(id)!!)!!.rejection)
            }
        }
    }

    @Test fun shortRussianTextIsUnchangedAndSnapshotMetadataFreezes() {
        val config = ModelConfigResolver.resolve(null, null, TestPipeline.FAKE_CONFIG, null)
        val input = "Короткий русский текст с огласовками שָׁלוֹם и emoji 🌍."
        val request = BriefRequestFactory.build(listOf(input), Lang.RU, Lang.EN, config, null)
        println("SHORT_RU serializedUtf8Bytes=${ChatRequestBody.build(request, null, true).toString().toByteArray().size}; outputReserve=${request.maxTokens}; protocolMargin=1024")
        assertEquals(Messages.brief(Lang.RU, Lang.EN, input), request.user)
        assertEquals(Prompts.BRIEF, request.system)
        assertEquals(Schemas.BRIEF, request.schema)
        val snapshot = ProcessingSnapshot.capture(config, null)
        assertEquals(snapshot, snapshot.resolveLegacyCapabilities(CatalogSnapshot(emptyList(), 2)))
    }

}
