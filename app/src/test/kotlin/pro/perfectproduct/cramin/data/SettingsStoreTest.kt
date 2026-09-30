package pro.perfectproduct.cramin.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pro.perfectproduct.cramin.data.db.Direction
import pro.perfectproduct.cramin.data.prefs.SettingsStore
import java.io.File

class SettingsStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun defaultsAndUpdates() = runTest {
        val store = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "settings.preferences_pb") })
        val defaults = store.current()
        assertEquals("ru", defaults.defaultTargetLang)
        assertEquals(Direction.SRC_FRONT, defaults.defaultDirection)
        assertEquals(3000, defaults.autoplayFrontMs)
        assertNull(defaults.modelOverridesJson)

        store.setDefaultTargetLang("he")
        store.setDefaultDirection(Direction.TGT_FRONT)
        store.setAutoplayIntervals(1500, 2500)
        store.setModelOverridesJson("{\"translate\":{\"model\":\"x\"}}")
        store.setAllDeckDirection("en", "ru", Direction.TGT_FRONT)
        val s = store.current()
        assertEquals("he", s.defaultTargetLang)
        assertEquals(Direction.TGT_FRONT, s.defaultDirection)
        assertEquals(1500, s.autoplayFrontMs)
        assertEquals(2500, s.autoplayBackMs)
        assertEquals("{\"translate\":{\"model\":\"x\"}}", s.modelOverridesJson)
        assertEquals(Direction.TGT_FRONT, store.allDeckDirection("en", "ru").first())
        assertNull(store.allDeckDirection("he", "ru").first())
    }
}
