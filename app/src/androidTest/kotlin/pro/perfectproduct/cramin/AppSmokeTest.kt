package pro.perfectproduct.cramin

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pro.perfectproduct.cramin.app.CraminApp

/** Пустой инструментированный тест: проверяет, что цикл сборка → эмулятор работает (фаза 0). */
@RunWith(AndroidJUnit4::class)
class AppSmokeTest {
    @Test
    fun applicationIdIsDebugVariant() {
        val app = ApplicationProvider.getApplicationContext<CraminApp>()
        assertEquals("pro.perfectproduct.cramin.debug", app.packageName)
        assertTrue(app.container.appContext === app)
    }
}
