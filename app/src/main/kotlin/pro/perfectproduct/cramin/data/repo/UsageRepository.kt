package pro.perfectproduct.cramin.data.repo

import kotlinx.coroutines.flow.Flow
import pro.perfectproduct.cramin.data.db.CraminDatabase
import pro.perfectproduct.cramin.data.db.JobEntity
import pro.perfectproduct.cramin.data.db.UsageTotals
import pro.perfectproduct.cramin.util.Clock

/** Учёт токенов и стоимости (SPEC §9.7 «Статистика», §6.3). */
class UsageRepository(
    private val db: CraminDatabase,
    private val clock: Clock,
) {
    fun observeTotals(): Flow<UsageTotals> = db.documentDao().observeUsageTotals()

    suspend fun jobs(documentId: Long): List<JobEntity> = db.jobDao().getByDocument(documentId)

    /** Пересчитывает итоги документа из его Job-строк; вызывается после каждого учтённого вызова. */
    suspend fun refreshDocumentTotals(documentId: Long) {
        val sum = db.jobDao().sumUsage(documentId)
        db.documentDao().setUsage(
            id = documentId,
            promptTokens = sum.promptTokens.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            completionTokens = sum.completionTokens.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            costUsd = sum.costUsd,
            now = clock.now(),
        )
    }
}
