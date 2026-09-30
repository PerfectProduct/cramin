package pro.perfectproduct.cramin.pipeline

/** Счёт слов для лимитов пайплайна: токен — последовательность непробельных символов с буквой или цифрой. */
object WordCounter {
    private val WS = Regex("\\s+")

    fun count(text: String): Int {
        if (text.isBlank()) return 0
        var n = 0
        for (token in text.split(WS)) {
            if (token.any { it.isLetterOrDigit() }) n++
        }
        return n
    }

    fun count(sentences: Iterable<SentenceDraft>): Int = sentences.sumOf { it.words }
}
