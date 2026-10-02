package pro.perfectproduct.cramin.ingest

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pro.perfectproduct.cramin.app.CraminApp
import pro.perfectproduct.cramin.app.MainActivity
import pro.perfectproduct.cramin.data.db.DocStatus
import pro.perfectproduct.cramin.testing.TestContainer

/** Real PDF and Android picker/share; real WorkManager, fake LLM, no external API. */
@RunWith(AndroidJUnit4::class)
class PdfEntryTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private fun pdf(name: String): Uri {
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
        })!!
        val pdf = PdfDocument()
        try {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(600, 800, 1).create())
            val paint = Paint().apply { textSize = 16f }
            listOf("The river bank was quiet in the morning.", "The library opened near the bridge.",
                "A teacher explained the story to the children.", "They walked along the river and watched the birds.",
                "The bank keeps money safely for the town.").forEachIndexed { i, line -> page.canvas.drawText(line, 30f, 60f + i * 32, paint) }
            pdf.finishPage(page)
            context.contentResolver.openOutputStream(uri)!!.use(pdf::writeTo)
        } finally { pdf.close() }
        return uri
    }

    @Test fun pickerAndShareKeepPdfAfterUriDisappearsAndReachReady() = runBlocking<Unit> {
        val app = context.applicationContext as CraminApp
        val original = app.container
        val container = TestContainer(context)
        container.settingsStore.setOnboardingDone(true)
        container.settingsStore.setNotificationsAsked(true)
        container.secretStore.setApiKey("synthetic-test-only-key")
        app.container = container
        val name = "cramin-synthetic-${System.nanoTime()}.pdf"
        val picked = pdf(name)
        var shared: Uri? = null
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            compose.onNodeWithTag("libraryCreate").performClick()
            compose.onNodeWithTag("sourcePdf").performClick()
            assertTrue("Android picker did not show synthetic PDF", device.wait(Until.hasObject(By.text(name)), 10_000))
            device.findObject(By.text(name)).click()
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("submitPdf")).fetchSemanticsNodes().isNotEmpty() }
            context.contentResolver.delete(picked, null, null)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            compose.onNodeWithTag("submitPdf").performScrollTo().performClick()
            awaitReady(container, 1)
            shared = pdf("cramin-share-synthetic.pdf")
            scenario.close()
            scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_SEND)
                .setType("application/pdf").putExtra(Intent.EXTRA_STREAM, shared).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("submitPdf")).fetchSemanticsNodes().isNotEmpty() }
            context.contentResolver.delete(shared, null, null)
            compose.onNodeWithTag("submitPdf").performScrollTo().performClick()
            awaitReady(container, 2)
            assertTrue(container.fakeLlm.requests.isNotEmpty())
            assertEquals(2, container.db.documentDao().getAll().count { it.status == DocStatus.READY })
        } finally {
            scenario?.close()
            context.contentResolver.delete(picked, null, null)
            shared?.let { context.contentResolver.delete(it, null, null) }
            container.workManager.cancelAllWork().result.get()
            app.container = original
            container.db.close()
        }
    }

    private suspend fun awaitReady(container: TestContainer, expected: Int) {
        withTimeout(30_000) {
            while (container.db.documentDao().getAll().count { it.status == DocStatus.READY } < expected) {
                val failed = container.db.documentDao().getAll().firstOrNull { it.status == DocStatus.FAILED }
                assertNull("PDF processing failed: ${failed?.errorCode}", failed)
                delay(100)
            }
        }
    }
}
