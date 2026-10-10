package pro.perfectproduct.cramin.ui.create

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pro.perfectproduct.cramin.R
import pro.perfectproduct.cramin.app.AppContainer
import pro.perfectproduct.cramin.data.repo.NewDocument
import pro.perfectproduct.cramin.ingest.UrlClassifier
import pro.perfectproduct.cramin.pipeline.LangDetector
import pro.perfectproduct.cramin.util.Lang
import pro.perfectproduct.cramin.util.Log
import java.io.File

data class CreateState(
    val targetLang: Lang = Lang.RU,
    val hasKey: Boolean = false,
    val textProvider: pro.perfectproduct.cramin.llm.TextProvider = pro.perfectproduct.cramin.llm.TextProvider.OPENROUTER,
    val textModel: String? = null,
    val notificationsAsked: Boolean = true,
    val pdfName: String? = null,
    val sourceLangNeeded: Boolean = false,
    val sourceLang: Lang? = null,
    val error: String? = null,
    val busy: Boolean = false,
    val done: Boolean = false,
)

class CreateViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(CreateState())
    val state: StateFlow<CreateState> = _state
    private var pdfTemp: File? = null

    init {
        viewModelScope.launch {
            val s = container.settingsStore.current()
            _state.update { it.copy(targetLang = Lang.fromCode(s.defaultTargetLang) ?: Lang.RU, notificationsAsked = s.notificationsAsked) }
            kotlinx.coroutines.flow.combine(container.settingsStore.settings, container.secretStore.hasApiKey, container.chatGpt.changes) { s, key, version -> Triple(s,key,version) }.collect { (settings, _, _) ->
                _state.update { it.copy(textProvider = settings.textProvider, textModel = settings.chatGptModel) }
                val ready = container.textProviderReady()
                _state.update { it.copy(hasKey = ready) }
            }
        }
    }

    fun setTargetLang(lang: Lang) = _state.update { it.copy(targetLang = lang, error = null) }
    fun setSourceLang(lang: Lang) = _state.update { it.copy(sourceLang = lang, error = null) }
    fun markNotificationsAsked() = viewModelScope.launch { container.settingsStore.setNotificationsAsked(true) }

    fun submitUrl(raw: String) {
        val url = raw.trim()
        if (!UrlClassifier.isUrl(UrlClassifier.normalize(url)) || url.isBlank() || !url.contains('.')) {
            _state.update { it.copy(error = container.appContext.getString(R.string.create_url_invalid)) }
            return
        }
        val normalized = UrlClassifier.normalize(url)
        val target = _state.value.targetLang
        create(if (UrlClassifier.isYoutube(normalized)) NewDocument.Youtube(normalized, target) else NewDocument.Url(normalized, target))
    }

    /** Вставленный текст: язык определяется сразу, совпадение с языком перевода блокирует импорт (SPEC §3). */
    fun submitText(text: String, title: String) {
        val trimmed = text.trim()
        val ctx = container.appContext
        if (trimmed.isEmpty()) {
            _state.update { it.copy(error = ctx.getString(R.string.create_text_empty)) }
            return
        }
        val detected = LangDetector.detect(trimmed)
        val lang = detected ?: _state.value.sourceLang
        if (lang == null) {
            _state.update { it.copy(sourceLangNeeded = true, error = null) }
            return
        }
        if (lang == _state.value.targetLang) {
            _state.update { it.copy(error = ctx.getString(R.string.create_same_language)) }
            return
        }
        create(NewDocument.Text(trimmed, title.trim().takeIf { it.isNotEmpty() }, _state.value.targetLang, lang))
    }

    /** PDF копируется во временный файл сразу: права на content:// временные (CRM-DL-022). */
    fun pickPdf(context: Context, uri: Uri) = viewModelScope.launch {
        _state.update { it.copy(busy = true, error = null) }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val name = displayName(context, uri) ?: "document.pdf"
                val tmp = File(context.cacheDir, "import-${System.nanoTime()}.pdf")
                context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    ?: error("no stream")
                name to tmp
            }
        }
        result.onSuccess { (name, tmp) ->
            pdfTemp?.delete()
            pdfTemp = tmp
            _state.update { it.copy(busy = false, pdfName = name) }
        }.onFailure { e ->
            Log.w(TAG, "pdf pick failed: ${e.javaClass.simpleName}")
            _state.update { it.copy(busy = false, error = context.getString(R.string.create_pdf_failed)) }
        }
    }

    fun submitPdf() {
        val tmp = pdfTemp ?: return
        val name = _state.value.pdfName ?: "document.pdf"
        create(NewDocument.Pdf(name, { tmp.inputStream() }, _state.value.targetLang), after = { tmp.delete(); pdfTemp = null })
    }

    private fun create(request: NewDocument, after: () -> Unit = {}) = viewModelScope.launch {
        _state.update { it.copy(busy = true, error = null) }
        try {
            if (!container.textProviderReady()) {
                _state.update { it.copy(busy = false, error = container.appContext.getString(R.string.provider_not_ready)) }
                return@launch
            }
            val config = container.processingConfig()
            val snapshot = pro.perfectproduct.cramin.llm.ProcessingSnapshot.capture(config,
                if (config.provider == pro.perfectproduct.cramin.llm.TextProvider.OPENROUTER) container.modelCatalog.cached() else null)
            val id = withContext(Dispatchers.IO) {
                container.documentRepository.create(request, snapshot.encode(), pro.perfectproduct.cramin.pipeline.DocumentProcessor.PIPELINE_VERSION)
            }
            container.processScheduler.enqueue(id)
            after()
            _state.update { it.copy(busy = false, done = true) }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: pro.perfectproduct.cramin.llm.LlmException) {
            _state.update { it.copy(busy = false, error = container.appContext.getString(pro.perfectproduct.cramin.ui.components.errorMessageRes(pro.perfectproduct.cramin.pipeline.PipelineException.from(e).code))) }
        } catch (e: Exception) {
            Log.w(TAG, "create failed: ${e.javaClass.simpleName}")
            _state.update { it.copy(busy = false, error = container.appContext.getString(R.string.err_storage)) }
        }
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    companion object {
        private const val TAG = "Create"
    }
}
