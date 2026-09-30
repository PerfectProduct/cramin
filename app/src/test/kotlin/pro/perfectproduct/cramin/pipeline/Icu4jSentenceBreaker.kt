package pro.perfectproduct.cramin.pipeline

import com.ibm.icu.text.BreakIterator
import pro.perfectproduct.cramin.util.Lang

/** JVM-двойник AndroidSentenceBreaker: тот же ICU (android.icu — репак ICU4J). */
class Icu4jSentenceBreaker : SentenceBreaker {
    override fun sentenceEnds(text: String, lang: Lang): List<Int> {
        val it = BreakIterator.getSentenceInstance(lang.locale)
        it.setText(text)
        val ends = ArrayList<Int>()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            ends += end
            end = it.next()
        }
        return ends
    }
}

/** Стоп-листы из файлов репозитория для JVM-тестов (на устройстве — res/raw). */
object TestStoplists {
    val instance: Stoplists = Stoplists { lang ->
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .map { java.io.File(it, "src/main/res/raw") }
            .first { it.isDirectory }
        java.io.File(root, Stoplists.fileName(lang)).readText()
    }
}
