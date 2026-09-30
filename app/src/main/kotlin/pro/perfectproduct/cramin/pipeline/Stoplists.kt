package pro.perfectproduct.cramin.pipeline

import pro.perfectproduct.cramin.util.Lang

/**
 * Страховочные стоп-листы служебных слов (SPEC §6.6, п. 4): `res/raw/stop_{en,ru,he}.txt`.
 * Загрузчик подставляется снаружи: на Android — ресурсы, в JVM-тестах — файлы репозитория.
 */
class Stoplists(private val loader: (Lang) -> String) {
    private val cache = HashMap<Lang, Set<String>>()

    @Synchronized
    fun forLang(lang: Lang): Set<String> = cache.getOrPut(lang) { parse(loader(lang), lang) }

    companion object {
        fun fileName(lang: Lang): String = "stop_${lang.code}.txt"

        fun parse(text: String, lang: Lang): Set<String> = text.lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .map { TextNormalizer.normalize(it, lang) }
            .toSet()

        val EMPTY = Stoplists { "" }
    }
}
