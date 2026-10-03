package pro.perfectproduct.cramin.pipeline

/**
 * Вход брифа (SPEC §6.4): весь текст, если он не длиннее лимита; иначе первые 20 000 слов
 * плюс равномерно выбранные абзацы до лимита.
 */
object BriefInput {
    const val HEAD_WORDS = 20_000

    fun select(paragraphs: List<String>, maxInputWords: Int): String {
        require(maxInputWords > 0)
        val counts = paragraphs.map { WordCounter.count(it) }
        val total = counts.sum()
        if (total <= maxInputWords) return paragraphs.joinToString("\n\n")
        val chosen = ArrayList<Int>()
        var words = 0
        var i = 0
        while (i < paragraphs.size && words + counts[i] <= minOf(HEAD_WORDS, maxInputWords)) {
            chosen += i
            words += counts[i]
            i++
        }
        val rest = (i until paragraphs.size).toList()
        val budget = maxInputWords - words
        if (rest.isNotEmpty() && budget > 0) {
            val avg = rest.sumOf { counts[it] }.toDouble() / rest.size
            val want = (budget / avg).toInt().coerceAtLeast(1)
            val step = (rest.size.toDouble() / want).coerceAtLeast(1.0)
            var pos = 0.0
            var used = 0
            while (pos < rest.size) {
                val idx = rest[pos.toInt()]
                if (used + counts[idx] > budget) break
                chosen += idx
                used += counts[idx]
                pos += step
            }
        }
        if (chosen.isEmpty()) return takeWords(paragraphs.firstOrNull { it.isNotBlank() }.orEmpty(), maxInputWords)
        return chosen.sorted().joinToString("\n\n") { paragraphs[it] }
    }
    private fun takeWords(text: String, limit: Int): String {
        var count = 0
        for (token in Regex("\\S+").findAll(text)) {
            if (token.value.any { it.isLetterOrDigit() } && ++count > limit)
                return text.substring(0, token.range.first).trimEnd()
        }
        return text
    }
}
