package pro.perfectproduct.cramin.data.repo

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import pro.perfectproduct.cramin.data.db.CardCounts
import pro.perfectproduct.cramin.data.db.CardEntity
import pro.perfectproduct.cramin.data.db.CardStatus
import pro.perfectproduct.cramin.data.db.CraminDatabase
import pro.perfectproduct.cramin.data.db.LangPair
import pro.perfectproduct.cramin.data.db.OccurrenceRow
import pro.perfectproduct.cramin.data.db.SenseEntity
import pro.perfectproduct.cramin.pipeline.TextNormalizer
import pro.perfectproduct.cramin.util.Clock
import pro.perfectproduct.cramin.util.Lang

class CardRepository(
    private val db: CraminDatabase,
    private val clock: Clock,
) {
    private val cards get() = db.cardDao()

    fun observeCounts(documentId: Long): Flow<CardCounts> = cards.observeCounts(documentId)
    fun observeCards(documentId: Long): Flow<List<CardEntity>> = cards.observeByDocument(documentId)
    fun observeOccurrences(documentId: Long): Flow<List<OccurrenceRow>> = cards.observeOccurrencesByDocument(documentId)
    fun observeLangPairs(): Flow<List<LangPair>> = cards.observeLangPairs()
    fun observeUnlearnedCountForPair(lang: Lang, targetLang: Lang): Flow<Int> =
        cards.observeUnlearnedCountForPair(lang.code, targetLang.code)

    /** Колода документа в порядке первого появления в тексте (SPEC §10.3). */
    suspend fun deckCards(documentId: Long, filter: DeckFilter): List<StudyCard> {
        val entities = cards.getByDocument(documentId).filter {
            when (filter) {
                DeckFilter.UNLEARNED -> it.status != CardStatus.KNOWN
                DeckFilter.ALL -> true
                DeckFilter.STARRED -> it.starred
            }
        }
        return build(entities)
    }

    /**
     * Общая колода «Все невыученные» (SPEC §10.6): карточки со статусом не KNOWN из готовых документов,
     * дедупликация по (srcLang, tgtLang, lemmaKey, meaningKey) — остаётся карточка из самого свежего документа,
     * один смысл и его пример берутся у представителя группы.
     */
    suspend fun sharedDeckCards(lang: Lang, targetLang: Lang, requiredIds: List<Long> = emptyList(), originalGroups: Map<Long, List<Long>> = emptyMap()): List<StudyCard> = db.withTransaction {
        val all = cards.getCardsForPair(lang.code, targetLang.code)
        val byKey = LinkedHashMap<String, MutableList<CardEntity>>()
        for (c in all) byKey.getOrPut(c.lemmaKey + "\u0000" + c.meaningKey) { mutableListOf() }.add(c)
        // Карточка считается невыученной, если хотя бы один дубликат не KNOWN (свайп ставит статус всем).
        val groups = byKey.values.filter { g -> g.any { it.status != CardStatus.KNOWN || it.id in requiredIds || originalGroups.values.any { ids -> it.id in ids } } }
        val primaries = groups.map { it.first() }
        val built = build(primaries + groups.flatMap { it.drop(1) }).associateBy { it.id }
        val merged = groups.mapNotNull { group ->
            val primary = built[group.first().id] ?: return@mapNotNull null
            primary.copy(
                status = if (group.all { it.status == CardStatus.KNOWN }) CardStatus.KNOWN else primary.status,
                senses = primary.senses.take(1),
                duplicateIds = group.drop(1).map { it.id },
            )
        }
        // Retain saved representatives even if a newer duplicate became the primary.
        merged + requiredIds.mapNotNull { id ->
            val group = groups.firstOrNull { g -> g.any { it.id == id || it.id in originalGroups[id].orEmpty() } } ?: return@mapNotNull null
            merged.firstOrNull { it.id == group.first().id }?.takeIf { it.id != id }?.copy(id = id)
        }
    }

    /** Карточки сохранённой сессии по id — колода могла измениться (часть уже KNOWN), но сессия продолжается по своему порядку. */
    suspend fun cardsByIds(ids: List<Long>): List<StudyCard> {
        if (ids.isEmpty()) return emptyList()
        val entities = ids.chunked(CHUNK).flatMap { cards.getCards(it) }.associateBy { it.id }
        return build(ids.mapNotNull { entities[it] })
    }

    suspend fun card(cardId: Long): StudyCard? {
        val entity = cards.getCard(cardId) ?: return null
        return build(listOf(entity)).firstOrNull()
    }

    fun statusStore(shared: Boolean, sharedCard: (Long) -> StudyCard? = { null }) = object : pro.perfectproduct.cramin.study.CardStatusStore {
        override suspend fun get(cardId: Long) = getStatus(cardId)
        override suspend fun snapshot(cardId: Long): Map<Long, CardStatus> {
            val group = if (shared) sharedCard(cardId) else null
            return if (group == null) statusSnapshot(cardId, shared)
            else cards.getMeaningCards(group.lang.code, group.targetLang.code, group.lemmaKey, group.meaningKey).associate { it.id to it.status }
        }
        override suspend fun set(cardId: Long, status: CardStatus) { setStatus(cardId, status) }
    }

    suspend fun statusSnapshot(cardId: Long, shared: Boolean): Map<Long, CardStatus> {
        val card = cards.getCard(cardId) ?: return emptyMap()
        return (if (shared) cards.getMeaningCards(card.lang, card.targetLang, card.lemmaKey, card.meaningKey) else listOf(card))
            .associate { it.id to it.status }
    }

    suspend fun getStatus(cardId: Long): CardStatus? = cards.getStatus(cardId)

    suspend fun setStatus(cardId: Long, status: CardStatus) = cards.setStatus(cardId, status, clock.now())

    /** Свайп в общей колоде меняет статус у всех дубликатов (SPEC §10.6). */
    suspend fun setStatusForMeaning(lang: Lang, targetLang: Lang, lemmaKey: String, meaningKey: String, status: CardStatus) =
        cards.setStatusForMeaning(lang.code, targetLang.code, lemmaKey, meaningKey, status, clock.now())

    suspend fun setStarred(cardId: Long, starred: Boolean) = cards.setStarred(cardId, starred, clock.now())

    private suspend fun build(entities: List<CardEntity>): List<StudyCard> {
        if (entities.isEmpty()) return emptyList()
        val ids = entities.map { it.id }
        val senses = HashMap<Long, MutableList<SenseEntity>>()
        val occurrences = HashMap<Long, OccurrenceRow>()
        db.withTransaction {
            ids.chunked(CHUNK).forEach { chunk ->
                cards.getSensesForCards(chunk).forEach { senses.getOrPut(it.cardId) { mutableListOf() }.add(it) }
                cards.getOccurrencesForCards(chunk).forEach { occurrences[it.occurrence.id] = it }
            }
        }
        return entities.map { c ->
            StudyCard(
                id = c.id,
                documentId = c.documentId,
                lemmaKey = c.lemmaKey,
                meaningKey = c.meaningKey,
                lemma = c.lemma,
                lemmaVocalized = c.lemmaVocalized,
                pos = c.pos,
                lang = Lang.requireCode(c.lang),
                targetLang = Lang.requireCode(c.targetLang),
                status = c.status,
                starred = c.starred,
                firstSentenceIdx = c.firstSentenceIdx,
                senses = senses[c.id].orEmpty().sortedBy { it.idx }.map { s ->
                    val ex = s.exampleOccurrenceId?.let { occurrences[it] }
                    StudySense(
                        id = s.id,
                        translation = s.translation,
                        example = ex?.let {
                            StudyExample(
                                sentenceIdx = it.sentenceIdx,
                                sentence = it.sentenceText,
                                start = it.occurrence.start,
                                end = it.occurrence.end,
                                translation = it.segmentTranslation,
                                targetStart = it.occurrence.targetStart,
                                targetEnd = it.occurrence.targetEnd,
                            )
                        },
                    )
                },
            )
        }
    }

    companion object {
        /** SQLite на API 26 ограничивает число переменных запроса 999. */
        private const val CHUNK = 500
    }
}
