package pro.perfectproduct.cramin.testing

import pro.perfectproduct.cramin.util.Lang

/** Фикстуры en/ru/he из `src/test/resources/fixtures/` (видны и инструментированным тестам). */
object Fixtures {
    fun text(lang: Lang): String {
        val name = "fixtures/${lang.code}.txt"
        val stream = Fixtures::class.java.classLoader?.getResourceAsStream(name)
            ?: error("fixture $name not on classpath")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
