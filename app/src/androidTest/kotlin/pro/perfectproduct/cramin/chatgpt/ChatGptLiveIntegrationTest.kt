package pro.perfectproduct.cramin.chatgpt

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import pro.perfectproduct.cramin.R
import pro.perfectproduct.cramin.app.CraminApp
import pro.perfectproduct.cramin.app.MainActivity
import pro.perfectproduct.cramin.data.db.DocStatus
import pro.perfectproduct.cramin.data.repo.DeckFilter
import pro.perfectproduct.cramin.llm.*
import java.io.File

/** Explicit, charged synthetic live test. Uses the installed application's real container/vault/workers. */
@RunWith(AndroidJUnit4::class)
@pro.perfectproduct.cramin.LiveApi
class ChatGptLiveIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<CraminApp>()
    private val container get() = app.container
    private val directory get() = File(app.getExternalFilesDir(null),"chatgpt-integration-evidence").apply { mkdirs() }
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun await(tag: String, millis: Long = 60_000) { compose.waitUntil(millis) { exists(tag) } }
    private fun tap(tag: String) { await(tag); val node = compose.onNodeWithTag(tag); try { node.performScrollTo() } catch (_: AssertionError) {} ; node.performClick() }
    private fun screenshot(name: String) { compose.waitForIdle(); Thread.sleep(600); UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(directory,"$name.png")) }
    private fun save(value: JsonObject) { File(directory,"live-e2e.json").writeText(Json { prettyPrint=true }.encodeToString(JsonObject.serializer(),value)) }

    @Test fun normalSettingsToSyntheticTextReadyCardsAndStudy() = runBlocking {
        Assume.assumeTrue("Requires explicit ChatGPT plan live authorization",InstrumentationRegistry.getArguments().getString("chatgptLive") == "true")
        assertTrue(app.packageName in setOf("pro.perfectproduct.cramin.debug", "pro.perfectproduct.cramin"))
        assertEquals("pro.perfectproduct.cramin.app.AppContainer",container.javaClass.name)
        assertTrue("Prepared OAuth session must exist",container.chatGpt.connected())
        val renewal = if (InstrumentationRegistry.getArguments().getString("verifyRenewal") == "true")
            container.chatGpt.verifyRenewal() else buildJsonObject { put("status", "not_requested") }
        File(directory,"live-refresh.json").writeText(renewal.toString())
        val start = System.currentTimeMillis()
        ActivityScenario.launch(MainActivity::class.java).use { _ ->
            compose.waitUntil(30_000) { exists("onboardingStart") || exists("librarySettings") }
            if (exists("onboardingStart")) tap("onboardingStart")
            tap("librarySettings"); tap("settingsProcessing"); tap("provider-CHATGPT_PLAN")
            tap("chatGptCatalog")
            compose.waitUntil(120_000) { compose.onNodeWithTag("chatGptModel").fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled).not() }
            tap("chatGptModel"); tap("chatGptModel-gpt-6.1-sol")
            compose.waitUntil(30_000) { runBlocking { container.settingsStore.current().chatGptModel == "gpt-6.1-sol" } }
            screenshot("01-normal-settings")
            compose.onNodeWithContentDescription(app.getString(R.string.action_back)).performClick()
            compose.onNodeWithContentDescription(app.getString(R.string.action_back)).performClick()
            tap("libraryCreate"); await("pasteField")
            compose.onNodeWithTag("targetLang").onChildren().filter(hasText("Русский")).onFirst().performClick()
            compose.onNodeWithTag("pasteField").performTextInput(SYNTHETIC)
            compose.waitUntil(60_000) { compose.onNodeWithTag("submitText").fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled).not() }
            screenshot("02-synthetic-input")
            tap("submitText")
            var id: Long? = null
            compose.waitUntil(30_000) {
                container.db.openHelper.readableDatabase.query("SELECT id FROM Document WHERE createdAt >= ? AND sourceType = 'TEXT' ORDER BY id DESC LIMIT 1",arrayOf(start)).use { cursor ->
                    if (cursor.moveToFirst()) { id=cursor.getLong(0); true } else false
                }
            }
            val documentId = requireNotNull(id)
            val states = linkedSetOf<String>()
            var doc = requireNotNull(container.db.documentDao().getById(documentId))
            val deadline = System.currentTimeMillis()+360_000
            while (System.currentTimeMillis()<deadline) {
                doc = requireNotNull(container.db.documentDao().getById(documentId));states += doc.status.name
                if (doc.status in setOf(DocStatus.READY,DocStatus.FAILED)) break
                kotlinx.coroutines.delay(500)
            }
            val cards=container.cardRepository.deckCards(documentId,DeckFilter.ALL)
            val segments=container.db.segmentDao().getByDocument(documentId)
            val jobs=container.db.jobDao().getByDocument(documentId)
            val snapshot=ProcessingSnapshot.decode(requireNotNull(doc.modelsSnapshotJson))
            val terminals = File(container.files.dir(documentId),"response-evidence").listFiles().orEmpty()
                .sortedBy { it.name }.map { Json.parseToJsonElement(it.readText()) }
            val openRouterKeyPresent=container.secretStore.hasApiKey.first()
            val evidence=buildJsonObject {
                put("terminal_evidence",JsonArray(terminals))
                put("openrouter_key_present",openRouterKeyPresent)
                put("synthetic_input",SYNTHETIC); put("started_at_epoch_ms",start);put("finished_at_epoch_ms",System.currentTimeMillis())
                put("document_id",documentId);put("status",doc.status.name);put("error_code",doc.errorCode);put("failure",doc.failureJson?.let { Json.parseToJsonElement(it) } ?: JsonNull)
                put("snapshot",Json.parseToJsonElement(snapshot.encode()));put("cost_usd",doc.costUsd?.let { JsonPrimitive(it) } ?: JsonNull)
                put("prompt_tokens",doc.promptTokens);put("completion_tokens",doc.completionTokens);put("observed_states",JsonArray(states.map(::JsonPrimitive)))
                putJsonArray("segments") { segments.forEach { addJsonObject { put("from",it.firstSentenceIdx);put("to",it.lastSentenceIdx);put("translation",it.translation) } } }
                putJsonArray("jobs") { jobs.forEach { job -> addJsonObject { put("kind",job.kind.name);put("status",job.status.name);put("model",job.model);put("attempts",job.attempts);put("finish_reason",job.finishReason);put("prompt_tokens",job.promptTokens);put("completion_tokens",job.completionTokens);put("cost_usd",job.costUsd?.let { JsonPrimitive(it) } ?: JsonNull);put("response",job.responseJson?.let { runCatching { Json.parseToJsonElement(it) }.getOrElse { JsonPrimitive("non_json") } } ?: JsonNull) } } }
                putJsonArray("cards") { cards.forEach { card -> addJsonObject { put("id",card.id);put("lemma",card.lemma);put("pos",card.pos.name);putJsonArray("senses") { card.senses.forEach { sense -> addJsonObject { put("translation",sense.translation);put("example",sense.example?.sentence) } } } } } }
                put("study_opened",false)
            }
            save(evidence)
            assertEquals("Actual pipeline failed; inspect synthetic evidence",DocStatus.READY,doc.status)
            assertEquals(TextProvider.CHATGPT_PLAN,snapshot.provider)
            assertTrue(snapshot.config.roles.values.filter { it.role.isText }.all { it.provider == TextProvider.CHATGPT_PLAN && it.model == "gpt-6.1-sol" })
            assertNull(doc.costUsd)
            assertTrue("Live consolidation must be exercised by ambiguous bank meanings", jobs.any { it.kind.name == "CONSOLIDATE" && it.status.name == "DONE" })
            assertTrue(terminals.isNotEmpty() && terminals.all { it.jsonObject["status"] == JsonPrimitive("completed") })
            assertTrue("Meaningful nonempty cards required",cards.size >= 3 && cards.all { it.lemma.isNotBlank() && it.senses.any { s -> s.translation.any { c -> c in 'А'..'я' } } })
            val translation=segments.joinToString(" ") { it.translation }.lowercase()
            assertTrue(translation.contains("вод") && (translation.contains("лёд") || translation.contains("льд") || translation.contains("лед")))
            assertTrue(jobs.any { it.kind.name == "TRANSLATE" && it.status.name == "DONE" })
            tap("doc-$documentId");tap("tabCards");screenshot("03-ready-cards")
            tap("studyButton");await("flashCard");compose.onNodeWithTag("counter").assertIsDisplayed()
            compose.onNodeWithTag("cardFront").assertIsDisplayed()
            screenshot("04-study-front")
            compose.onNodeWithTag("flashCard").performClick();await("cardBack");compose.waitForIdle();compose.onNodeWithTag("cardBack").assertIsDisplayed();screenshot("05-study-back")
            save(JsonObject(evidence + mapOf("study_opened" to JsonPrimitive(true),"study_flip_performed" to JsonPrimitive(true))))
            // Leave the normal study screen visible for owner review.
        }
    }
    companion object { const val SYNTHETIC="The bank lends money to local businesses. This bank offers savings accounts. The bank of the river is covered with grass. Children are playing on the bank and watching the water. Water freezes at zero degrees Celsius. Ice floats because it is less dense than liquid water." }
}
