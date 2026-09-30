package pro.perfectproduct.cramin.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// Модель данных Room, схема v1 (SPEC §13). Имена таблиц — как в спецификации.
// Enum'ы хранятся строками (Room делает это сам), поэтому конвертеры не нужны.

enum class SourceType { URL, YOUTUBE, PDF, TEXT }

enum class DocStatus {
    QUEUED, FETCHING, TRANSCRIBING, BRIEFING, TRANSLATING, EXTRACTING, CONSOLIDATING, READY, FAILED;

    val isTerminal: Boolean get() = this == READY || this == FAILED
}

enum class Direction { SRC_FRONT, TGT_FRONT }

enum class JobKind { BRIEF, TRANSLATE, EXTRACT, CONSOLIDATE, STT }

enum class JobStatus { PENDING, DONE, FAILED }

enum class CardStatus { NEW, LEARNING, KNOWN }

enum class Pos { NOUN, VERB, ADJ, ADV, PHRASAL_VERB, IDIOM, COLLOCATION }

@Entity(tableName = "Document")
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val emoji: String,
    val sourceType: SourceType,
    val sourceRef: String,
    /** Код языка источника (en/ru/he) или пустая строка, пока язык не определён. */
    val sourceLang: String,
    val targetLang: String,
    val status: DocStatus,
    /** Доля выполнения 0..1. */
    val progress: Float,
    val errorCode: String?,
    val errorMessage: String?,
    val direction: Direction?,
    val pipelineVersion: Int,
    val briefJson: String?,
    val modelsSnapshotJson: String?,
    val promptTokens: Int,
    val completionTokens: Int,
    val costUsd: Double?,
    val audioSeconds: Int,
    val wordCount: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "Sentence",
    foreignKeys = [
        ForeignKey(entity = DocumentEntity::class, parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["documentId", "idx"], unique = true), Index("segmentId")],
)
data class SentenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    /** Сквозной индекс предложения в документе. */
    val idx: Int,
    val paragraphIdx: Int,
    val text: String,
    val segmentId: Long?,
)

@Entity(
    tableName = "Segment",
    foreignKeys = [
        ForeignKey(entity = DocumentEntity::class, parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["documentId", "firstSentenceIdx"], unique = true)],
)
data class SegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    val firstSentenceIdx: Int,
    val lastSentenceIdx: Int,
    val translation: String,
)

@Entity(
    tableName = "Job",
    foreignKeys = [
        ForeignKey(entity = DocumentEntity::class, parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["documentId", "kind", "idx"], unique = true)],
)
data class JobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    val kind: JobKind,
    val idx: Int,
    val rangeStart: Int?,
    val rangeEnd: Int?,
    val status: JobStatus,
    val attempts: Int,
    val model: String,
    val responseJson: String?,
    val finishReason: String?,
    val promptTokens: Int,
    val completionTokens: Int,
    val costUsd: Double?,
    val updatedAt: Long,
)

@Entity(
    tableName = "Card",
    foreignKeys = [
        ForeignKey(entity = DocumentEntity::class, parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(value = ["documentId", "lemmaKey"], unique = true),
        Index(value = ["lang", "targetLang", "lemmaKey"]),
        Index("status"),
    ],
)
data class CardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    val lemmaKey: String,
    val lemma: String,
    val lemmaVocalized: String?,
    val pos: Pos,
    val lang: String,
    val targetLang: String,
    val status: CardStatus,
    val starred: Boolean,
    val firstSentenceIdx: Int,
    val updatedAt: Long,
    // Поля под интервальное повторение (SPEC §10.2): в v1 не используются.
    val dueAt: Long? = null,
    val intervalDays: Int? = null,
    val ease: Double? = null,
    val reps: Int? = null,
    val lapses: Int? = null,
)

@Entity(
    tableName = "Sense",
    foreignKeys = [
        ForeignKey(entity = CardEntity::class, parentColumns = ["id"], childColumns = ["cardId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("cardId")],
)
data class SenseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: Long,
    val idx: Int,
    val translation: String,
    /** Ссылка на Occurrence без внешнего ключа: связь циклическая (Occurrence.senseId). */
    val exampleOccurrenceId: Long?,
)

@Entity(
    tableName = "Occurrence",
    foreignKeys = [
        ForeignKey(entity = CardEntity::class, parentColumns = ["id"], childColumns = ["cardId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = SenseEntity::class, parentColumns = ["id"], childColumns = ["senseId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = SentenceEntity::class, parentColumns = ["id"], childColumns = ["sentenceId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("cardId"), Index("senseId"), Index("sentenceId")],
)
data class OccurrenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: Long,
    val senseId: Long?,
    val sentenceId: Long,
    val surface: String,
    val targetSurface: String?,
    /** Смещения поверхностной формы в тексте предложения; null, если форма не найдена. */
    val start: Int?,
    val end: Int?,
    /** Смещения внутри Segment.translation. */
    val targetStart: Int?,
    val targetEnd: Int?,
    val isExample: Boolean,
)

@Entity(tableName = "StudySession")
data class StudySessionEntity(
    /** `doc:{id}:{filter}` или `all:{src}-{tgt}` (SPEC §13). */
    @PrimaryKey val deckKey: String,
    val stateJson: String,
    val updatedAt: Long,
)
