package pro.perfectproduct.cramin.study

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import pro.perfectproduct.cramin.util.Lang
import pro.perfectproduct.cramin.util.Log
import java.util.Locale

/**
 * Системный TextToSpeech (SPEC §10.5): Locale("en"), Locale("ru"), Locale("iw"/"he").
 * Недоступные языки скрывают 🔊 в UI; в настройках показывается подсказка установить голос.
 */
class TtsController(context: Context) {
    private val _available = MutableStateFlow<Set<Lang>>(emptySet())
    val available: StateFlow<Set<Lang>> = _available.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    @Volatile
    private var onDone: (() -> Unit)? = null

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            _available.value = Lang.entries.filter { lang -> localesFor(lang).any { isAvailable(it) } }.toSet()
            _ready.value = true
            Log.i(TAG, "tts ready, languages=${_available.value.map { it.code }}")
        } else {
            Log.w(TAG, "tts init failed: $status")
        }
    }.also { engine ->
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { onDone?.invoke() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { onDone?.invoke() }
        })
    }

    private fun isAvailable(locale: Locale): Boolean {
        val engine = tts ?: return false
        val r = runCatching { engine.isLanguageAvailable(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        return r >= TextToSpeech.LANG_AVAILABLE
    }

    private fun localesFor(lang: Lang): List<Locale> = when (lang) {
        Lang.HE -> listOf(Locale.forLanguageTag("he"), Locale.forLanguageTag("iw"), Locale("iw", "IL"), Locale("he", "IL"))
        else -> listOf(lang.locale)
    }

    fun isAvailable(lang: Lang): Boolean = lang in _available.value

    /** Читает текст; `rate` — скорость речи из настроек. `onDone` вызывается по окончании (автопроигрывание с озвучкой). */
    fun speak(text: String, lang: Lang, rate: Float = 1f, onDone: (() -> Unit)? = null) {
        val engine = tts ?: return onDone?.invoke() ?: Unit
        if (!isAvailable(lang) || text.isBlank()) {
            onDone?.invoke()
            return
        }
        this.onDone = onDone
        val locale = localesFor(lang).firstOrNull { isAvailable(it) } ?: lang.locale
        engine.language = locale
        engine.setSpeechRate(rate.coerceIn(0.5f, 2f))
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cramin-${System.nanoTime()}")
    }

    fun stop() {
        onDone = null
        tts?.stop()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
    }

    companion object {
        private const val TAG = "Tts"
    }
}
