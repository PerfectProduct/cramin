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
    @Test fun documentPreparationSurvivesReopeningStoreWithoutChangingGlobalSettings() = runTest {
        val file = File(tmp.root, "reopen.preferences_pb")
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val first = SettingsStore(PreferenceDataStoreFactory.create(scope = firstScope) { file })
        first.setAutoSpeak(true)
        first.setAutoplayIntervals(1500, 4500)
        first.toggleAllCategory(4)
        first.setDocumentFilter(12, pro.perfectproduct.cramin.data.repo.DeckFilter.UNLEARNED_STARRED)
        first.setDocumentShuffle(12, true)
        firstScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        firstScope.coroutineContext[kotlinx.coroutines.Job]?.join()
        val reopened = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { file })
        assertEquals(pro.perfectproduct.cramin.data.repo.DeckFilter.UNLEARNED_STARRED, reopened.documentFilter(12).first())
        assertEquals(true, reopened.documentShuffle(12).first())
        assertEquals(pro.perfectproduct.cramin.data.repo.DeckFilter.UNLEARNED, reopened.documentFilter(99).first())
        assertEquals(true, reopened.current().autoSpeak)
        assertEquals(4500, reopened.current().autoplayBackMs)
        assertEquals(3, reopened.allCategoryMask.first())
    }

}
