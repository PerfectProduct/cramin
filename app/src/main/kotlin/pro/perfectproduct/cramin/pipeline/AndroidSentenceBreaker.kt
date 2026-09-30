package pro.perfectproduct.cramin.pipeline

import android.icu.text.BreakIterator
import pro.perfectproduct.cramin.util.Lang

/** `android.icu.text.BreakIterator.getSentenceInstance(locale)` (SPEC §5.1). */
class AndroidSentenceBreaker : SentenceBreaker {
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
