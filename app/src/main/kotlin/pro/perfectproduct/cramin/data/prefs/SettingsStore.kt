package pro.perfectproduct.cramin.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import pro.perfectproduct.cramin.data.db.Direction

/** Настройки изучения и приложения (SPEC §9.7). Меняются только добавлением ключей с дефолтами (SPEC §12.5). */
data class Settings(
    val onboardingDone: Boolean = false,
    val defaultTargetLang: String = "ru",
    val defaultDirection: Direction = Direction.SRC_FRONT,
    val autoSpeak: Boolean = false,
    val ttsRate: Float = 1.0f,
    val autoplayFrontMs: Int = 3000,
    val autoplayBackMs: Int = 3000,
    /** Переопределения моделей по ролям, JSON (SPEC §6.12, источник 1). */
    val modelOverridesJson: String? = null,
    /** Кэш удалённого конфига моделей и его ETag (SPEC §6.12, источник 2). */
    val remoteModelsJson: String? = null,
    val remoteModelsEtag: String? = null,
    val remoteModelsUpdatedAt: Long = 0L,
    val notificationsAsked: Boolean = false,
)

class SettingsStore(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<Settings> = dataStore.data.map { p ->
        Settings(
            onboardingDone = p[Keys.ONBOARDING_DONE] ?: false,
            defaultTargetLang = p[Keys.DEFAULT_TARGET_LANG] ?: "ru",
            defaultDirection = p[Keys.DEFAULT_DIRECTION]?.let { runCatching { Direction.valueOf(it) }.getOrNull() } ?: Direction.SRC_FRONT,
            autoSpeak = p[Keys.AUTO_SPEAK] ?: false,
            ttsRate = p[Keys.TTS_RATE] ?: 1.0f,
            autoplayFrontMs = p[Keys.AUTOPLAY_FRONT_MS] ?: 3000,
            autoplayBackMs = p[Keys.AUTOPLAY_BACK_MS] ?: 3000,
            modelOverridesJson = p[Keys.MODEL_OVERRIDES_JSON],
            remoteModelsJson = p[Keys.REMOTE_MODELS_JSON],
            remoteModelsEtag = p[Keys.REMOTE_MODELS_ETAG],
            remoteModelsUpdatedAt = p[Keys.REMOTE_MODELS_UPDATED_AT] ?: 0L,
            notificationsAsked = p[Keys.NOTIFICATIONS_ASKED] ?: false,
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun setOnboardingDone(done: Boolean) = edit { it[Keys.ONBOARDING_DONE] = done }
    suspend fun setDefaultTargetLang(lang: String) = edit { it[Keys.DEFAULT_TARGET_LANG] = lang }
    suspend fun setDefaultDirection(direction: Direction) = edit { it[Keys.DEFAULT_DIRECTION] = direction.name }
    suspend fun setAutoSpeak(enabled: Boolean) = edit { it[Keys.AUTO_SPEAK] = enabled }
    suspend fun setTtsRate(rate: Float) = edit { it[Keys.TTS_RATE] = rate }
    suspend fun setAutoplayIntervals(frontMs: Int, backMs: Int) = edit {
        it[Keys.AUTOPLAY_FRONT_MS] = frontMs
        it[Keys.AUTOPLAY_BACK_MS] = backMs
    }
    suspend fun setNotificationsAsked(asked: Boolean) = edit { it[Keys.NOTIFICATIONS_ASKED] = asked }

    suspend fun setModelOverridesJson(json: String?) = edit {
        if (json == null) it.remove(Keys.MODEL_OVERRIDES_JSON) else it[Keys.MODEL_OVERRIDES_JSON] = json
    }

    suspend fun setRemoteModels(json: String?, etag: String?, updatedAt: Long) = edit {
        if (json == null) it.remove(Keys.REMOTE_MODELS_JSON) else it[Keys.REMOTE_MODELS_JSON] = json
        if (etag == null) it.remove(Keys.REMOTE_MODELS_ETAG) else it[Keys.REMOTE_MODELS_ETAG] = etag
        it[Keys.REMOTE_MODELS_UPDATED_AT] = updatedAt
    }

    /** Направление общей колоды хранится отдельно для каждой пары языков (SPEC §10.6). */
    fun allDeckDirection(lang: String, targetLang: String): Flow<Direction?> = dataStore.data.map { p ->
        p[stringPreferencesKey("all_deck_direction_$lang-$targetLang")]?.let { runCatching { Direction.valueOf(it) }.getOrNull() }
    }

    suspend fun setAllDeckDirection(lang: String, targetLang: String, direction: Direction) = edit {
        it[stringPreferencesKey("all_deck_direction_$lang-$targetLang")] = direction.name
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit { block(it) }
    }

    private object Keys {
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val DEFAULT_TARGET_LANG = stringPreferencesKey("default_target_lang")
        val DEFAULT_DIRECTION = stringPreferencesKey("default_direction")
        val AUTO_SPEAK = booleanPreferencesKey("auto_speak")
        val TTS_RATE = floatPreferencesKey("tts_rate")
        val AUTOPLAY_FRONT_MS = intPreferencesKey("autoplay_front_ms")
        val AUTOPLAY_BACK_MS = intPreferencesKey("autoplay_back_ms")
        val MODEL_OVERRIDES_JSON = stringPreferencesKey("model_overrides_json")
        val REMOTE_MODELS_JSON = stringPreferencesKey("remote_models_json")
        val REMOTE_MODELS_ETAG = stringPreferencesKey("remote_models_etag")
        val REMOTE_MODELS_UPDATED_AT = longPreferencesKey("remote_models_updated_at")
        val NOTIFICATIONS_ASKED = booleanPreferencesKey("notifications_asked")
    }
}
