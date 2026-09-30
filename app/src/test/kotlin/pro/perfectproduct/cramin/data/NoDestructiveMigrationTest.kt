package pro.perfectproduct.cramin.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SPEC §12.5: `fallbackToDestructiveMigration*` запрещён — прогресс изучения должен переживать
 * обновления. Проверяем исходники main, потому что билдер Room нельзя опросить в рантайме.
 */
class NoDestructiveMigrationTest {
    @Test
    fun mainSourcesNeverCallFallbackToDestructiveMigration() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "src/main/kotlin") }
            .firstOrNull { it.isDirectory }
            ?: error("src/main/kotlin не найден относительно ${File("").absolutePath}")
        val comments = Regex("/\\*.*?\\*/|//[^\\n]*", RegexOption.DOT_MATCHES_ALL)
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { comments.replace(it.readText(), "").contains("fallbackToDestructiveMigration") }
            .map { it.relativeTo(root).path }
            .toList()
        assertTrue("Найден запрещённый вызов в: $offenders", offenders.isEmpty())
    }

    @Test
    fun schemaV1IsExported() {
        val schema = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "schemas/pro.perfectproduct.cramin.data.db.CraminDatabase/1.json") }
            .firstOrNull { it.isFile }
        assertTrue("Схема v1 не экспортирована в app/schemas", schema != null)
    }
}
