package pro.perfectproduct.cramin.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pro.perfectproduct.cramin.data.db.CraminDatabase

/**
 * Схема v1 создаётся из экспортированного JSON (SPEC §14.2). Заготовка для будущих миграций:
 * добавьте версию, миграцию в CraminDatabase.MIGRATIONS и вызов runMigrationsAndValidate.
 */
@RunWith(AndroidJUnit4::class)
class SchemaMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CraminDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun createV1FromExportedSchema() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO Document(title, emoji, sourceType, sourceRef, sourceLang, targetLang, status, progress,
                    pipelineVersion, promptTokens, completionTokens, audioSeconds, wordCount, createdAt, updatedAt)
                VALUES ('t', '📄', 'TEXT', '', 'en', 'ru', 'READY', 1.0, 1, 0, 0, 0, 0, 1, 1)
                """.trimIndent(),
            )
            db.query("SELECT COUNT(*) FROM Document").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
            }
            db.query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").use { c ->
                val names = generateSequence { if (c.moveToNext()) c.getString(0) else null }.toSet()
                for (t in listOf("Document", "Sentence", "Segment", "Job", "Card", "Sense", "Occurrence", "StudySession")) {
                    assertTrue("нет таблицы $t", t in names)
                }
            }
        }
    }

    @Test
    fun migrationsFromV1ToCurrentValidate() {
        // При VERSION == 1 миграций нет: проверка сводится к валидации схемы v1. Для v2+ helper
        // прогонит CraminDatabase.MIGRATIONS и сверит результат с экспортированной схемой.
        helper.createDatabase(TEST_DB, 1).close()
        helper.runMigrationsAndValidate(TEST_DB, CraminDatabase.VERSION, true, *CraminDatabase.MIGRATIONS).close()
    }

    companion object {
        private const val TEST_DB = "migration-test.db"
    }
}
