package pro.perfectproduct.cramin.data.repo

import pro.perfectproduct.cramin.data.db.CardEntity
import pro.perfectproduct.cramin.data.db.CardStatus
import pro.perfectproduct.cramin.data.db.TopicCategory

object CategoryFilter {
    const val ALL = 7
    // Unknown is not GENERAL: full-set mode retains legacy cards; subsets explicitly report incompleteness.
    fun accepts(category: TopicCategory?, mask: Int): Boolean =
        if (category == null) mask == ALL else mask and category.bit != 0
    fun accepts(card: CardEntity, mask: Int, filter: DeckFilter): Boolean = accepts(card.category, mask) && when (filter) {
        DeckFilter.ALL -> true
        DeckFilter.UNLEARNED -> card.status != CardStatus.KNOWN
        DeckFilter.STARRED -> card.starred
    }
}
