package pro.perfectproduct.cramin.release

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import pro.perfectproduct.cramin.app.CraminApp
import pro.perfectproduct.cramin.app.theme.CraminTheme
import pro.perfectproduct.cramin.ui.settings.LicensesScreen

/** Exercises the real packaged assets; no network, test container or paid API. */
class ReleaseLicensesTest {
    @get:Rule val compose = createComposeRule()
    private val assets = ApplicationProvider.getApplicationContext<CraminApp>().assets
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
        compose.setContent { CraminTheme { LicensesScreen {} } }
        compose.onNodeWithText("Cramin APK — GPL-3.0-or-later").performClick()
        compose.onNodeWithText("Scope: эти условия относятся", substring = true).assertExists()
    }
}
