package pro.perfectproduct.cramin.data.repo

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.withLock
import pro.perfectproduct.cramin.data.db.CraminDatabase
import pro.perfectproduct.cramin.data.db.Direction
import pro.perfectproduct.cramin.data.db.DocStatus
import pro.perfectproduct.cramin.data.db.DocumentEntity
import pro.perfectproduct.cramin.data.db.DocumentWithCounts
import pro.perfectproduct.cramin.data.db.SourceType
import pro.perfectproduct.cramin.util.Clock
import pro.perfectproduct.cramin.util.Lang
import pro.perfectproduct.cramin.util.Log
import java.io.File
import java.io.InputStream

/** Что импортирует пользователь (SPEC §9.2, §7). */
sealed interface NewDocument {
    val targetLang: Lang

    data class Url(val url: String, override val targetLang: Lang) : NewDocument
    data class Youtube(val url: String, override val targetLang: Lang) : NewDocument
    data class Text(val text: String, val title: String?, override val targetLang: Lang, val sourceLang: Lang?) : NewDocument
    data class Pdf(val displayName: String, val open: () -> InputStream, override val targetLang: Lang) : NewDocument
}

class DocumentRepository(
    private val db: CraminDatabase,
    private val files: DocumentFiles,
    private val clock: Clock,
    private val checkpoint: (String) -> Unit = {},
) {
    private val documents get() = db.documentDao()

    fun observeLibrary(): Flow<List<DocumentWithCounts>> = documents.observeLibrary()
    fun observeDocument(id: Long): Flow<DocumentEntity?> = documents.observeById(id)
    fun observeDocumentWithCounts(id: Long): Flow<DocumentWithCounts?> = documents.observeWithCounts(id)
    fun observeReadyCount(): Flow<Int> = documents.observeReadyCount()

    suspend fun get(id: Long): DocumentEntity? = documents.getById(id)

    /** Создаёт документ в статусе QUEUED и сохраняет входные данные в файлы. Обработку ставит в очередь вызывающий. */
    suspend fun create(request: NewDocument): Long {
        val now = clock.now()
        val (type, ref, title, sourceLang) = when (request) {
            is NewDocument.Url -> Quad(SourceType.URL, request.url, request.url.removePrefix("https://").removePrefix("http://"), "")
            is NewDocument.Youtube -> Quad(SourceType.YOUTUBE, request.url, "YouTube", "")
            is NewDocument.Text -> Quad(SourceType.TEXT, "", request.title?.takeIf { it.isNotBlank() } ?: firstLine(request.text), request.sourceLang?.code.orEmpty())
            is NewDocument.Pdf -> Quad(SourceType.PDF, request.displayName, request.displayName.removeSuffix(".pdf"), "")
        }
        val id = documents.insert(
            DocumentEntity(
                title = title.take(MAX_TITLE),
                emoji = DEFAULT_EMOJI,
                sourceType = type,
                sourceRef = ref,
                sourceLang = sourceLang,
                targetLang = request.targetLang.code,
                status = DocStatus.QUEUED,
                progress = 0f,
                errorCode = null,
                errorMessage = null,
                direction = null,
                pipelineVersion = 0,
                briefJson = null,
                modelsSnapshotJson = null,
                promptTokens = 0,
                completionTokens = 0,
                costUsd = null,
                audioSeconds = 0,
                wordCount = 0,
                createdAt = now,
                updatedAt = now,
            ),
        )
        // Каталог нового документа должен быть пустым: остатки от прежнего id (тесты, восстановление
        // резервной копии) иначе подхватятся как уже извлечённый текст.
        files.deleteAll(id)
        when (request) {
            is NewDocument.Text -> files.inputText(id).writeText(request.text)
            is NewDocument.Pdf -> request.open().use { input -> files.inputPdf(id).outputStream().use { input.copyTo(it) } }
            else -> Unit
        }
        Log.i(TAG, "created document $id type=$type target=${request.targetLang.code}")
        return id
    }

    suspend fun rename(id: Long, title: String) {
        documents.setTitle(id, title.trim().take(MAX_TITLE), clock.now())
    }

    suspend fun delete(id: Long) {
        db.withTransaction {
            documents.delete(id)
            db.studySessionDao().deleteByPrefix("doc:$id:")
        }
        files.deleteAll(id)
        Log.i(TAG, "deleted document $id")
    }

    suspend fun setDirection(id: Long, direction: Direction) = documents.setDirection(id, direction, clock.now())

    suspend fun setTargetLang(id: Long, lang: Lang) = documents.setTargetLang(id, lang.code, clock.now())

    suspend fun setSourceLang(id: Long, lang: Lang) = documents.setSourceLang(id, lang.code, clock.now())

    /** Возвращает документ в очередь (повтор после ошибки). Продолжение с места сбоя обеспечивает пайплайн. */
    suspend fun requeue(id: Long) {
        documents.setStatus(id, DocStatus.QUEUED, 0f, null, null, clock.now())
    }

    /**
     * «Обработать заново» (SPEC §6.11): удаляет производные данные, но статусы карточек
     * запоминает по lemmaKey; пайплайн восстановит их после извлечения.
     */
    suspend fun prepareReprocess(id: Long): List<pro.perfectproduct.cramin.data.db.CardStatusSnapshot> =
        db.documentLock(id).withLock {
            db.withTransaction {
                val progress = ReprocessProgress(db, files)
                val alreadyPending = db.reprocessDao().get(id)?.pending == true
                val snapshot = progress.capture(id)
                checkpoint("snapshot")
                // Repeated requests retain both the snapshot and completed work of this attempt.
                if (!alreadyPending) {
                    db.cardDao().deleteByDocument(id)
                    db.jobDao().deleteByDocument(id)
                    db.segmentDao().deleteByDocument(id)
                    db.sentenceDao().deleteByDocument(id)
                    db.studySessionDao().deleteByPrefix("doc:$id:")
                    documents.setUsage(id, 0, 0, null, clock.now())
                }
                checkpoint("deleted")
                documents.setStatus(id, DocStatus.QUEUED, 0f, null, null, clock.now())
                snapshot
            }.also { checkpoint("prepared") }
        }

    fun sourceTextFile(id: Long): File = files.sourceText(id)

    private fun firstLine(text: String): String =
        text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(MAX_TITLE) ?: "Текст"

    private data class Quad(val type: SourceType, val ref: String, val title: String, val sourceLang: String)

    companion object {
        private const val TAG = "DocumentRepo"
        const val MAX_TITLE = 120
        const val DEFAULT_EMOJI = "📄"
    }
}
