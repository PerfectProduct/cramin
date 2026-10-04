package pro.perfectproduct.cramin.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import pro.perfectproduct.cramin.app.theme.CraminTheme
import pro.perfectproduct.cramin.data.db.*
import pro.perfectproduct.cramin.testing.TestContainer
import pro.perfectproduct.cramin.ui.document.*
import java.io.File

class TopicFiltersUiTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val container = TestContainer(context)
    @After fun close() { container.db.close() }

    @Test fun normalFont() = check(1f, false)
    @Test fun font150Narrow() = check(1.5f, false)
    @Test fun font200Narrow() = check(2f, false)
    @Test fun hebrewRtlFont200Narrow() = check(2f, true)

    private fun check(scale: Float, rtl: Boolean) {
        val doc = runBlocking {
            val id = container.db.documentDao().insert(DocumentEntity(title = if (rtl) "למידת רגרסיה לינארית" else "Линейная регрессия", emoji = "📊", sourceType = SourceType.TEXT,
                sourceRef = "", sourceLang = if (rtl) "he" else "en", targetLang = "ru", status = DocStatus.READY,
                progress = 1f, errorCode = null, errorMessage = null, direction = null, pipelineVersion = 1, briefJson = null,
                modelsSnapshotJson = null, promptTokens = 0, completionTokens = 0, costUsd = null, audioSeconds = 0, wordCount = 12, createdAt = 1, updatedAt = 1))
            (0..3).forEach { index -> container.db.cardDao().insertCard(CardEntity(documentId = id, lemmaKey = "word$index", meaningKey = "$index", lemma = "word$index",
                lemmaVocalized = null, pos = Pos.NOUN, lang = if (rtl) "he" else "en", targetLang = "ru", status = CardStatus.NEW,
                starred = index == 0, firstSentenceIdx = index, updatedAt = 1, category = TopicCategory.entries.getOrNull(index))) }
            id
        }
        lateinit var vm: DocumentViewModel
        compose.setContent {
            vm = remember { DocumentViewModel(container, doc) }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale), LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                CraminTheme {
                    Column(Modifier.width(320.dp).height(620.dp)) {
                        Text(if (rtl) "למידת רגרסיה לינארית" else "Линейная регрессия")
                        CardsTab(vm, CardCounts(4,4,0,0,1), { _, _ -> })
                    }
                }
            }
        }
        compose.waitUntil(5000) { vm.selection.value.first == 4 }
        val stem = "topic-${(scale * 100).toInt()}-${if (rtl) "rtl" else "ltr"}"
        screenshot("$stem-top")
        for (category in TopicCategory.entries) {
            compose.onNodeWithTag("category_${category.name}").performScrollTo().assertIsDisplayed().performClick()
            compose.waitUntil(5000) { vm.categoryMask.value and category.bit == 0 }
        }
        compose.onNodeWithTag("studyButton").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        assertEquals(0, vm.selection.value.first)
        screenshot("$stem-empty")
        for (category in TopicCategory.entries) compose.onNodeWithTag("category_${category.name}").performScrollTo().performClick()
        compose.waitUntil(5000) { vm.selection.value.first == 4 }
        compose.onNodeWithText("Продолжить определение категорий").performScrollTo().assertIsDisplayed()
        screenshot("$stem-enrichment")
        compose.onNodeWithTag("studyButton").performScrollTo().assertIsDisplayed().assertIsEnabled().assertTextContains("4", substring = true)
        screenshot("$stem-study")
        assertTrue(container.fakeLlm.requests.isEmpty())
        runBlocking { assertEquals(7, container.db.documentDao().getById(doc)?.categoryMask) }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val output = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: requireNotNull(context.getExternalFilesDir(null)).path, "topic-screenshots").apply { mkdirs() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().useBitmap { bitmap ->
            File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    private fun Bitmap.useBitmap(block: (Bitmap) -> Unit) { try { block(this) } finally { recycle() } }
}
