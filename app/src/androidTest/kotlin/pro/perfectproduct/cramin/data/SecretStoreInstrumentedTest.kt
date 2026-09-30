package pro.perfectproduct.cramin.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import pro.perfectproduct.cramin.data.prefs.KeystoreCipher
import pro.perfectproduct.cramin.data.prefs.SecretStore
import java.io.File

/** SecretStore на настоящем Android Keystore (SPEC §14.2). */
@RunWith(AndroidJUnit4::class)
class SecretStoreInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var file: File
    private lateinit var store: SecretStore

    @Before
    fun setUp() {
        file = File(context.filesDir, "test-secrets-${System.nanoTime()}.preferences_pb")
        store = SecretStore(PreferenceDataStoreFactory.create(scope = scope) { file }, KeystoreCipher("cramin-test-secrets"))
    }

    @After
    fun tearDown() {
        scope.cancel()
        file.delete()
    }

    @Test
    fun encryptsDecryptsAndKeepsPlaintextOutOfFile() = runBlocking {
        val key = "sk-or-v1-0123456789abcdef0123456789abcdef"
        store.setApiKey(key)
        assertTrue(store.hasApiKey.first())
        assertEquals(key, store.getApiKey())
        assertEquals("cdef", store.apiKeyHint.first())
        val raw = file.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse("открытый ключ найден в файле DataStore", raw.contains(key.substring(0, 12)))
        store.clear()
        assertNull(store.getApiKey())
    }

    @Test
    fun keystoreCipherUsesFreshIvEachTime() {
        val cipher = KeystoreCipher("cramin-test-secrets")
        val a = cipher.encrypt("same".toByteArray())
        val b = cipher.encrypt("same".toByteArray())
        assertNotEquals(a.toList(), b.toList())
        assertEquals("same", String(cipher.decrypt(a) ?: ByteArray(0)))
        assertNull(cipher.decrypt(ByteArray(5)))
        val corrupted = a.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertNull(cipher.decrypt(corrupted))
    }
}
