package pro.perfectproduct.cramin.data.prefs

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import pro.perfectproduct.cramin.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Шифрует и расшифровывает короткие секреты. Реализация на Android Keystore — [KeystoreCipher];
 * JVM-тесты подставляют свою.
 */
interface SecretCipher {
    /** Возвращает `iv || ciphertext`. */
    fun encrypt(plain: ByteArray): ByteArray

    /** Возвращает null, если расшифровать нельзя (ключ Keystore утерян, данные повреждены). */
    fun decrypt(blob: ByteArray): ByteArray?
}

/**
 * AES-256-GCM ключом из Android Keystore без аутентификации пользователя (SPEC §11).
 * Ключ не покидает Keystore; после восстановления резервной копии на другом устройстве
 * шифротекст не расшифровать — [SecretStore] тогда считает, что ключа нет.
 */
class KeystoreCipher(private val alias: String = DEFAULT_ALIAS) : SecretCipher {

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return gen.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain)
        return iv + ct
    }

    override fun decrypt(blob: ByteArray): ByteArray? {
        if (blob.size <= IV_LENGTH) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, blob, 0, IV_LENGTH))
            cipher.doFinal(blob, IV_LENGTH, blob.size - IV_LENGTH)
        } catch (e: Exception) {
            // Причина не логируется подробно: она не нужна, а данные — секрет.
            Log.w(TAG, "decrypt failed: ${e.javaClass.simpleName}")
            null
        }
    }

    companion object {
        private const val TAG = "KeystoreCipher"
        const val DEFAULT_ALIAS = "cramin-secrets"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val TAG_BITS = 128
    }
}

/**
 * Хранилище ключа OpenRouter: шифротекст лежит в отдельном DataStore (`secrets`),
 * исключённом из резервного копирования (SPEC §11). Открытый ключ в файл не попадает.
 */
class SecretStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
) {
    /** Есть ли сохранённый шифротекст (без попытки расшифровать). */
    val hasApiKey: Flow<Boolean> = dataStore.data.map { it[KEY_CT] != null }

    /** Последние 4 символа ключа для показа в UI (SPEC §11); null, если ключа нет. */
    val apiKeyHint: Flow<String?> = dataStore.data.map { it[KEY_HINT] }

    suspend fun getApiKey(): String? {
        val ct = dataStore.data.first()[KEY_CT] ?: return null
        val blob = runCatching { Base64.decode(ct, Base64.NO_WRAP) }.getOrNull() ?: return null
        val plain = cipher.decrypt(blob) ?: return null
        return String(plain, Charsets.UTF_8)
    }

    suspend fun setApiKey(key: String) {
        val trimmed = key.trim()
        val blob = cipher.encrypt(trimmed.toByteArray(Charsets.UTF_8))
        dataStore.edit {
            it[KEY_CT] = Base64.encodeToString(blob, Base64.NO_WRAP)
            it[KEY_HINT] = trimmed.takeLast(4)
        }
    }

    suspend fun clear() {
        dataStore.edit {
            it.remove(KEY_CT)
            it.remove(KEY_HINT)
        }
    }

    companion object {
        const val DATASTORE_NAME = "secrets"
        val KEY_CT: Preferences.Key<String> = stringPreferencesKey("openrouter_key_ct")
        val KEY_HINT: Preferences.Key<String> = stringPreferencesKey("openrouter_key_hint")
    }
}
