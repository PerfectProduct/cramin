package pro.perfectproduct.cramin.pipeline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import pro.perfectproduct.cramin.R
import pro.perfectproduct.cramin.data.db.DocStatus
import pro.perfectproduct.cramin.util.Log

/**
 * Фоновая обработка документа (SPEC §6.11): уникальная работа `process-{id}`, KEEP, только при сети,
 * foreground-уведомление с прогрессом (тип dataSync). Логика — в [DocumentProcessor].
 */
class ProcessDocumentWorker(
    appContext: Context,
    params: WorkerParameters,
    private val processorFactory: () -> DocumentProcessor,
    private val titleProvider: suspend (Long) -> String?,
) : CoroutineWorker(appContext, params) {

    private val notifications = Notifications(applicationContext)
    private val documentId: Long = inputData.getLong(KEY_DOCUMENT_ID, -1L)

    override suspend fun doWork(): Result {
        if (documentId < 0) return Result.failure()
        val title = titleProvider(documentId) ?: applicationContext.getString(R.string.brand)
        runCatching { setForeground(foregroundInfo(title, DocStatus.QUEUED, 0f)) }
            .onFailure { Log.w(TAG, "setForeground failed: ${it.javaClass.simpleName}") }
        var lastShown = -1
        val outcome = if (inputData.getBoolean(KEY_TOPIC_ONLY, false)) processorFactory().enrichCategories(documentId)
        else processorFactory().process(documentId, inputData.getBoolean(KEY_LOCAL_CONSOLIDATION, false), inputData.getBoolean(KEY_CONSOLIDATION_ONLY, false)) { status, progress ->
            val percent = (progress * 100).toInt()
            if (percent != lastShown || status.isTerminal) {
                lastShown = percent
                runCatching { setForeground(foregroundInfo(title, status, progress)) }
            }
        }
        return when (outcome) {
            is ProcessOutcome.Ready -> {
                notifications.showDone(documentId, title, outcome.cardCount)
                Result.success(workDataOf(KEY_CARDS to outcome.cardCount))
            }
            is ProcessOutcome.Failed -> {
                notifications.showFailed(documentId, title)
                Result.failure(workDataOf(KEY_ERROR to outcome.code.name))
            }
            ProcessOutcome.Skipped -> Result.success()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(titleProvider(documentId) ?: applicationContext.getString(R.string.brand), DocStatus.QUEUED, 0f)

    private fun foregroundInfo(title: String, status: DocStatus, progress: Float): ForegroundInfo {
        val notification = notifications.progress(documentId, title, status, progress)
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(Notifications.progressId(documentId), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(Notifications.progressId(documentId), notification)
        }
    }

    companion object {
        private const val TAG = "Worker"
        const val KEY_TOPIC_ONLY = "topicOnly"
        const val KEY_CONSOLIDATION_ONLY = "consolidationOnly"
        const val KEY_LOCAL_CONSOLIDATION = "localConsolidationOnly"
        const val KEY_DOCUMENT_ID = "documentId"
        const val KEY_CARDS = "cards"
        const val KEY_ERROR = "error"

        fun uniqueName(documentId: Long) = "process-$documentId"
    }
}

/** Постановка обработки в очередь WorkManager. */
class ProcessScheduler(private val workManager: WorkManager) {
    fun enqueue(documentId: Long, localConsolidationOnly: Boolean = false, consolidationOnly: Boolean = false, topicOnly: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<ProcessDocumentWorker>()
            .setInputData(workDataOf(ProcessDocumentWorker.KEY_TOPIC_ONLY to topicOnly, ProcessDocumentWorker.KEY_DOCUMENT_ID to documentId, ProcessDocumentWorker.KEY_LOCAL_CONSOLIDATION to localConsolidationOnly, ProcessDocumentWorker.KEY_CONSOLIDATION_ONLY to consolidationOnly))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (localConsolidationOnly) NetworkType.NOT_REQUIRED else NetworkType.CONNECTED).build())
            .addTag(TAG_PROCESS)
            .build()
        workManager.enqueueUniqueWork(ProcessDocumentWorker.uniqueName(documentId), ExistingWorkPolicy.KEEP, request)
        Log.i("Scheduler", "enqueued doc=$documentId")
    }

    suspend fun cancelAndAwait(documentId: Long) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            workManager.cancelUniqueWork(ProcessDocumentWorker.uniqueName(documentId)).result.get()
        }
    }

    fun cancel(documentId: Long) {
        workManager.cancelUniqueWork(ProcessDocumentWorker.uniqueName(documentId))
    }

    companion object {
        const val TAG_PROCESS = "process-document"
    }
}

/** Уведомления обработки: канал прогресса (тихий) и канал результатов. */
class Notifications(private val context: Context) {
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, context.getString(R.string.notif_channel_progress), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULTS, context.getString(R.string.notif_channel_results), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun progress(documentId: Long, title: String, status: DocStatus, progress: Float): Notification =
        NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(statusLabel(status))
            .setProgress(100, (progress * 100).toInt(), status == DocStatus.QUEUED)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openDocument(documentId))
            .build()

    fun showDone(documentId: Long, title: String, cards: Int) {
        if (!canPost()) return
        val n = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_done_title, cards))
            .setContentText(title)
            .setAutoCancel(true)
            .setContentIntent(openDocument(documentId))
            .build()
        manager.notify(resultId(documentId), n)
    }

    fun showFailed(documentId: Long, title: String) {
        if (!canPost()) return
        val n = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_failed_title))
            .setContentText(title)
            .setAutoCancel(true)
            .setContentIntent(openDocument(documentId))
            .build()
        manager.notify(resultId(documentId), n)
    }

    private fun canPost(): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openDocument(documentId: Long): PendingIntent {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            putExtra(EXTRA_DOCUMENT_ID, documentId)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        } ?: Intent()
        return PendingIntent.getActivity(context, documentId.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun statusLabel(status: DocStatus): String = context.getString(
        when (status) {
            DocStatus.QUEUED -> R.string.status_queued
            DocStatus.FETCHING -> R.string.status_fetching
            DocStatus.TRANSCRIBING -> R.string.status_transcribing
            DocStatus.BRIEFING -> R.string.status_briefing
            DocStatus.TRANSLATING -> R.string.status_translating
            DocStatus.EXTRACTING -> R.string.status_extracting
            DocStatus.CONSOLIDATING -> R.string.status_consolidating
            DocStatus.READY -> R.string.status_ready
            DocStatus.FAILED -> R.string.status_failed
        },
    )

    companion object {
        const val CHANNEL_PROGRESS = "processing"
        const val CHANNEL_RESULTS = "results"
        const val EXTRA_DOCUMENT_ID = "pro.perfectproduct.cramin.DOCUMENT_ID"
        fun progressId(documentId: Long): Int = 1_000_000 + (documentId % 1_000_000).toInt()
        fun resultId(documentId: Long): Int = 2_000_000 + (documentId % 1_000_000).toInt()
    }
}
