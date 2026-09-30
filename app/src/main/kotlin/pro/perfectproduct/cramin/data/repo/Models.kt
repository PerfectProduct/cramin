package pro.perfectproduct.cramin.data.repo

import pro.perfectproduct.cramin.data.db.CardStatus
import pro.perfectproduct.cramin.data.db.Pos
import pro.perfectproduct.cramin.util.Lang

/** Фильтр колоды документа (SPEC §10.3). */
enum class DeckFilter(val key: String) {
    UNLEARNED("unlearned"),
    ALL("all"),
    STARRED("starred"),
    ;

    companion object {
        fun fromKey(key: String): DeckFilter? = entries.firstOrNull { it.key == key }
    }
}

/** Пример смысла: предложение с выделенной поверхностной формой и перевод сегмента с выделенным `ft`. */
data class StudyExample(
    val sentenceIdx: Int,
    val sentence: String,
    val start: Int?,
    val end: Int?,
    val translation: String?,
    val targetStart: Int?,
    val targetEnd: Int?,
)

data class StudySense(
    val id: Long,
    val translation: String,
    val example: StudyExample?,
)

/** Карточка для сессии и вкладки «Карточки». В общей колоде объединяет дубликаты (SPEC §10.6). */
data class StudyCard(
    val id: Long,
    val documentId: Long,
    val lemmaKey: String,
    val lemma: String,
    val lemmaVocalized: String?,
    val pos: Pos,
    val lang: Lang,
    val targetLang: Lang,
    val status: CardStatus,
    val starred: Boolean,
    val firstSentenceIdx: Int,
    val senses: List<StudySense>,
    /** Идентификаторы карточек-дубликатов в других документах (общая колода); пусто для колоды документа. */
    val duplicateIds: List<Long> = emptyList(),
) {
    /** Не больше четырёх смыслов на карточке; хвост остаётся в БД (SPEC §6.8). */
    val visibleSenses: List<StudySense> get() = senses.take(MAX_VISIBLE_SENSES)

    companion object {
        const val MAX_VISIBLE_SENSES = 4
    }
}

/** Ключ колоды (SPEC §13): `doc:{id}:{filter}` или `all:{src}-{tgt}`. */
sealed interface DeckKey {
    val key: String

    data class Document(val documentId: Long, val filter: DeckFilter) : DeckKey {
        override val key: String get() = "doc:$documentId:${filter.key}"
    }

    data class All(val lang: Lang, val targetLang: Lang) : DeckKey {
        override val key: String get() = "all:${lang.code}-${targetLang.code}"
    }

    companion object {
        fun parse(key: String): DeckKey? {
            val parts = key.split(":")
            return when {
                parts.size == 3 && parts[0] == "doc" -> {
                    val id = parts[1].toLongOrNull() ?: return null
                    val filter = DeckFilter.fromKey(parts[2]) ?: return null
                    Document(id, filter)
                }
                parts.size == 2 && parts[0] == "all" -> {
                    val langs = parts[1].split("-")
                    if (langs.size != 2) return null
                    All(Lang.fromCode(langs[0]) ?: return null, Lang.fromCode(langs[1]) ?: return null)
                }
                else -> null
            }
        }
    }
}
