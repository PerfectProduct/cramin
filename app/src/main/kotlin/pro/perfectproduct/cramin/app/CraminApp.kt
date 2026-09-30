package pro.perfectproduct.cramin.app

import android.app.Application
import androidx.work.Configuration
import pro.perfectproduct.cramin.util.Log

/**
 * Точка входа. Держит [AppContainer] (ручной DI, SPEC §5.1) и отдаёт WorkManager
 * конфигурацию с нашей фабрикой воркеров, чтобы воркеры получали зависимости
 * из контейнера, а не создавали их сами.
 */
class CraminApp : Application(), Configuration.Provider {

    /** Тесты подменяют контейнер до запуска экранов и воркеров. */
    @Volatile
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = AppContainer.create(this)
        Log.i(TAG, "started, debug=${Log.enabled}")
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(container.workerFactory)
            .setMinimumLoggingLevel(if (Log.enabled) android.util.Log.INFO else android.util.Log.ASSERT)
            .build()

    companion object {
        private const val TAG = "App"
    }
}
