package pro.perfectproduct.cramin.ui.settings

import pro.perfectproduct.cramin.chatgpt.*
import pro.perfectproduct.cramin.llm.TextProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pro.perfectproduct.cramin.app.AppContainer
import pro.perfectproduct.cramin.data.db.Direction
import pro.perfectproduct.cramin.data.db.UsageTotals
import pro.perfectproduct.cramin.data.prefs.Settings
import pro.perfectproduct.cramin.llm.CatalogModel
import pro.perfectproduct.cramin.llm.EffectiveConfig
import pro.perfectproduct.cramin.llm.KeyCheck
import pro.perfectproduct.cramin.llm.ModelRole
import pro.perfectproduct.cramin.llm.RoleOverride
import pro.perfectproduct.cramin.util.Lang

sealed interface KeyUi {
    data object Idle : KeyUi
    data object Checking : KeyUi
    data class Valid(val remainingUsd: Double?) : KeyUi
    data object Invalid : KeyUi
    data class Error(val detail: String) : KeyUi
}

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val settings: StateFlow<Settings?> = container.settingsStore.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val hasKey: StateFlow<Boolean> = container.secretStore.hasApiKey.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val keyHint: StateFlow<String?> = container.secretStore.apiKeyHint.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val effective: StateFlow<EffectiveConfig?> = container.modelConfigRepository.observeEffective().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val remoteUpdatedAt: StateFlow<Long> = container.modelConfigRepository.observeRemoteUpdatedAt().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)
    val usage: StateFlow<UsageTotals?> = container.usageRepository.observeTotals().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val ttsLangs: StateFlow<Set<Lang>> get() = container.tts.available

    private val _keyUi = MutableStateFlow<KeyUi>(KeyUi.Idle)
    val keyUi: StateFlow<KeyUi> = _keyUi
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    private val _chatStatus = MutableStateFlow("Проверка подключения…")
    val chatStatus: StateFlow<String> = _chatStatus
    private val _chatBusy = MutableStateFlow(false)
    val chatBusy: StateFlow<Boolean> = _chatBusy
    private val _chatModels = MutableStateFlow<List<AccountModel>>(emptyList())
    val chatModels: StateFlow<List<AccountModel>> = _chatModels
    private val login = ChatGptLogin(container.appContext)
    private var loginJob: Job? = null

    init { refreshChatGpt() }
    fun setProvider(value: TextProvider) = viewModelScope.launch { container.settingsStore.setTextProvider(value) }
    fun setChatGptModel(slug: String) = viewModelScope.launch {
        if (_chatModels.value.any { it.slug == slug }) container.settingsStore.setChatGptModel(slug)
    }
    fun refreshChatGpt() = viewModelScope.launch {
        if (_chatBusy.value) return@launch
        _chatBusy.value = true
        try {
            _chatStatus.value = container.chatGpt.checkConnection()
            container.chatGpt.connectionChanged()
            _chatModels.value = container.chatGpt.catalog(force = true)
            _chatStatus.value += " Каталог: ${_chatModels.value.size} моделей."
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { _chatStatus.value = chatGptConnectionError(e); _chatModels.value = emptyList() }
        finally { _chatBusy.value = false }
    }
    fun connectChatGpt(openBrowser: (String) -> Unit) {
        if (_chatBusy.value) return
        _chatBusy.value = true
        loginJob = viewModelScope.launch {
            try {
                _chatStatus.value = "Завершите вход в системном браузере."
                login.run(openBrowser)
                container.chatGpt.connectionChanged()
                _chatModels.value = container.chatGpt.catalog(force = true)
                _chatStatus.value = container.chatGpt.checkConnection()
            } catch (e: CancellationException) { _chatStatus.value = "Вход отменён."; throw e }
            catch (e: Exception) { _chatStatus.value = chatGptConnectionError(e) }
            finally { _chatBusy.value = false }
        }
    }
    fun cancelChatGptLogin() { login.close(); loginJob?.cancel() }
    fun disconnectChatGpt() = viewModelScope.launch {
        if (_chatBusy.value) return@launch
        _chatBusy.value = true
        try { _chatStatus.value = container.chatGpt.disconnect(); _chatModels.value = emptyList() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { _chatStatus.value = chatGptConnectionError(e) }
        finally { _chatBusy.value = false }
    }
    override fun onCleared() { login.close(); super.onCleared() }

    /** Сохраняет ключ и проверяет его через GET /api/v1/key (SPEC §9.7). Ключ не логируется. */
    fun saveAndCheckKey(key: String) = viewModelScope.launch {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return@launch
        container.secretStore.setApiKey(trimmed)
        checkKey()
    }

    fun checkKey() = viewModelScope.launch {
        val key = container.secretStore.getApiKey() ?: run { _keyUi.value = KeyUi.Invalid; return@launch }
        _keyUi.value = KeyUi.Checking
        _keyUi.value = when (val r = container.keyChecker.check(key)) {
            is KeyCheck.Valid -> KeyUi.Valid(r.limitRemainingUsd)
            KeyCheck.Invalid -> KeyUi.Invalid
            is KeyCheck.Error -> KeyUi.Error(r.detail)
        }
    }

    fun removeKey() = viewModelScope.launch {
        container.secretStore.clear()
        _keyUi.value = KeyUi.Idle
    }

    fun refreshModels() = viewModelScope.launch {
        _refreshing.value = true
        runCatching { container.modelConfigRepository.refreshAndResolve(forceCatalog = true) }
        _refreshing.value = false
    }

    fun resetOverrides() = viewModelScope.launch { container.modelConfigRepository.clearOverrides() }
    fun setOverride(role: ModelRole, override: RoleOverride?) = viewModelScope.launch { container.modelConfigRepository.setOverride(role, override) }

    suspend fun catalogModels(): List<CatalogModel> = container.modelCatalog.get()?.structuredOutputModels().orEmpty()

    fun setDefaultTargetLang(lang: Lang) = viewModelScope.launch { container.settingsStore.setDefaultTargetLang(lang.code) }
    fun setDefaultDirection(d: Direction) = viewModelScope.launch { container.settingsStore.setDefaultDirection(d) }
    fun setAutoSpeak(v: Boolean) = viewModelScope.launch { container.settingsStore.setAutoSpeak(v) }
    fun setTtsRate(v: Float) = viewModelScope.launch { container.settingsStore.setTtsRate(v) }
    fun setIntervals(front: Int, back: Int) = viewModelScope.launch { container.settingsStore.setAutoplayIntervals(front, back) }
    fun finishOnboarding(onDone: () -> Unit) = viewModelScope.launch {
        container.settingsStore.setOnboardingDone(true)
        onDone()
    }
}
