package pro.perfectproduct.cramin.data

import org.junit.Assert.*
import org.junit.Test
import pro.perfectproduct.cramin.data.db.*
import pro.perfectproduct.cramin.data.repo.*

class SelectionRegressionTest {
    @Test fun independentStatusAndStarIntersectEveryCategoryCombination() {
        for (mask in 0..7) for (category in listOf(null) + TopicCategory.entries) {
            for (status in CardStatus.entries) for (starred in listOf(false, true)) {
                val card = CardEntity(documentId = 1, lemmaKey = "word", meaningKey = "meaning", lemma = "word", lemmaVocalized = null,
                    pos = Pos.NOUN, lang = "en", targetLang = "ru", status = status, starred = starred, firstSentenceIdx = 0, updatedAt = 0, category = category)
                val categoryMatches = if (category == null) mask == 7 else mask and category.bit != 0
                assertEquals(categoryMatches, CategoryFilter.accepts(card, mask, DeckFilter.ALL))
                assertEquals(categoryMatches && starred, CategoryFilter.accepts(card, mask, DeckFilter.STARRED))
                assertEquals(categoryMatches && status != CardStatus.KNOWN, CategoryFilter.accepts(card, mask, DeckFilter.UNLEARNED))
                assertEquals(categoryMatches && starred && status != CardStatus.KNOWN, CategoryFilter.accepts(card, mask, DeckFilter.UNLEARNED_STARRED))
            }
        }
    }
    @Test fun oldSessionKeysAreUnchangedAndNewIntersectionHasItsOwnKey() {
        assertEquals("doc:12:starred", DeckKey.Document(12, DeckFilter.STARRED).key)
        assertEquals(DeckKey.Document(12, DeckFilter.STARRED), DeckKey.parse("doc:12:starred"))
        for (mask in 0..7) for (filter in DeckFilter.entries) {
            val deck = DeckKey.Document(12, filter, mask)
            assertEquals(deck, DeckKey.parse(deck.key))
        }
    }
}
