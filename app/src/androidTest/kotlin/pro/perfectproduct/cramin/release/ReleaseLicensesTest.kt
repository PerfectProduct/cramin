package pro.perfectproduct.cramin.release

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import pro.perfectproduct.cramin.app.CraminApp
import pro.perfectproduct.cramin.app.MainActivity
import pro.perfectproduct.cramin.R

/** Exercises the real packaged assets; no network, test container or paid API. */
class ReleaseLicensesTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app = ApplicationProvider.getApplicationContext<CraminApp>()
    private val assets = app.assets
    @Test fun packagedScopeAndOriginalNotices() {
        val index = JSONArray(assets.open("legal/index.json").bufferedReader().use { it.readText() })
        assertEquals("DISTRIBUTION-LICENSE.txt", index.getJSONObject(0).getString("file"))
        val scope = assets.open("legal/DISTRIBUTION-LICENSE.txt").bufferedReader().use { it.readText() }
        assertTrue(scope.contains("GPL-3.0-or-later"))
        assertTrue(scope.contains("MIT grant"))
        assertTrue(scope.contains("END OF TERMS AND CONDITIONS"))
        val originalGpl = assets.open("legal/001.txt").bufferedReader().use { it.readText() }
        assertTrue(scope.endsWith(originalGpl))
        val mit = assets.open("legal/CRAMIN-MIT.txt").bufferedReader().use { it.readText() }
        assertTrue(mit.contains("Permission is hereby granted"))
        for (i in 0 until index.length()) assets.open("legal/"+index.getJSONObject(i).getString("file")).close()
    }
    @Test fun distributionScopeIsReadableOffline() {
        runBlocking { app.container.settingsStore.setOnboardingDone(true) }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10000) {
                compose.onAllNodesWithContentDescription(app.getString(R.string.library_settings)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription(app.getString(R.string.library_settings)).performClick()
            compose.onNodeWithTag("settingsApp").performScrollTo().performClick()
            compose.onNodeWithText(app.getString(R.string.settings_licenses)).performScrollTo().performClick()
            compose.onNodeWithText("Cramin APK — GPL-3.0-or-later").performClick()
            compose.onNodeWithText("Scope: эти условия относятся", substring = true).assertExists()
        }
    }
}
