package pro.perfectproduct.cramin.util

import pro.perfectproduct.cramin.BuildConfig

/**
 * Единственная точка логирования приложения (SPEC §11).
 *
 * В release лога нет вообще: [enabled] ложно, и любой вызов — no-op, а [install]
 * ничего не меняет. В debug записи уходят в logcat через [sink]; тесты подменяют
 * sink и проверяют, что в лог не попадают тексты документов и ключ.
 *
 * Правило для вызывающих: в сообщениях только идентификаторы, счётчики и статусы.
 * Ни текста документа, ни перевода, ни ключа, ни заголовка Authorization.
 */
object Log {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    fun interface Sink {
        fun log(level: Level, tag: String, message: String, error: Throwable?)
    }

    /** Истинно только в debug-сборке; в release компилятор видит константу false. */
    val enabled: Boolean = BuildConfig.DEBUG

    @Volatile
    private var sink: Sink = if (enabled) LogcatSink else NoopSink

    /** Подмена приёмника (тесты). В release не действует. */
    fun install(newSink: Sink?) {
        if (enabled) sink = newSink ?: LogcatSink
    }

    fun d(tag: String, message: String) = write(Level.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = write(Level.INFO, tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = write(Level.WARN, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = write(Level.ERROR, tag, message, error)

    private fun write(level: Level, tag: String, message: String, error: Throwable?) {
        if (!enabled) return
        sink.log(level, tag, message, error)
    }

    private object NoopSink : Sink {
        override fun log(level: Level, tag: String, message: String, error: Throwable?) = Unit
    }

    private object LogcatSink : Sink {
        override fun log(level: Level, tag: String, message: String, error: Throwable?) {
            val t = "Cramin/$tag"
            when (level) {
                Level.DEBUG -> android.util.Log.d(t, message)
                Level.INFO -> android.util.Log.i(t, message)
                Level.WARN -> android.util.Log.w(t, message, error)
                Level.ERROR -> android.util.Log.e(t, message, error)
            }
        }
    }
}
