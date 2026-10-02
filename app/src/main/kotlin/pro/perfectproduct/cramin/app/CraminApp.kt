package pro.perfectproduct.cramin.app

import android.app.Application
import kotlinx.coroutines.launch
import androidx.work.Configuration
import pro.perfectproduct.cramin.util.Log

/**
 * Точка входа. Держит [AppContainer] (ручной DI, SPEC §5.1) и отдаёт WorkManager
 * конфигурацию с нашей фабрикой воркеров, чтобы воркеры получали зависимости
 * из контейнера, а не создавали их сами.
 */
open class CraminApp : Application(), Configuration.Provider {

    /** Тесты подменяют контейнер до запуска экранов и воркеров. */
    @Volatile
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = createContainer()
        // Recover pending nonterminal work, including death between cancellation and re-enqueue.
        container.appScope.launch {
            container.db.reprocessDao().queued().forEach { container.processScheduler.enqueue(it) }
        }
        Log.i(TAG, "started, debug=${Log.enabled}")
    }

    protected open fun createContainer(): AppContainer = AppContainer.create(this)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(object : androidx.work.WorkerFactory() {
                override fun createWorker(context: android.content.Context, name: String, params: androidx.work.WorkerParameters) =
                    container.workerFactory.createWorker(context, name, params)
            })
            .setMinimumLoggingLevel(if (Log.enabled) android.util.Log.INFO else android.util.Log.ASSERT)
            .build()

    companion object {
        private const val TAG = "App"
    }
}
