package pro.perfectproduct.cramin.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import pro.perfectproduct.cramin.data.prefs.SecretCipher
import pro.perfectproduct.cramin.data.prefs.SecretStore
import java.io.File

/** Обратимый «шифр» для JVM: XOR с константой плюс IV-заглушка; проверяет контракт SecretStore, не криптографию. */
class FakeCipher(var failDecrypt: Boolean = false) : SecretCipher {
    override fun encrypt(plain: ByteArray): ByteArray = ByteArray(12) { 7 } + plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    override fun decrypt(blob: ByteArray): ByteArray? =
        if (failDecrypt) null else blob.drop(12).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
}

@RunWith(RobolectricTestRunner::class)
class SecretStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var file: File

    private fun store(cipher: SecretCipher): SecretStore {
        file = File(tmp.root, "secrets.preferences_pb")
        return SecretStore(PreferenceDataStoreFactory.create(scope = scope) { file }, cipher)
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun roundTripAndHint() = runTest {
        val s = store(FakeCipher())
        assertFalse(s.hasApiKey.first())
        s.setApiKey("  sk-or-v1-abcdef1234  ")
        assertTrue(s.hasApiKey.first())
        assertEquals("sk-or-v1-abcdef1234", s.getApiKey())
        assertEquals("1234", s.apiKeyHint.first())
        // Открытый ключ не лежит в файле DataStore.
        val raw = file.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains("sk-or-v1-abcdef"))
        s.clear()
        assertFalse(s.hasApiKey.first())
        assertNull(s.getApiKey())
    }

    @Test
    fun undecryptableBlobReadsAsMissingKey() = runTest {
        val cipher = FakeCipher()
        val s = store(cipher)
        s.setApiKey("sk-or-v1-secret")
        cipher.failDecrypt = true
        assertNull(s.getApiKey())
        assertTrue(s.hasApiKey.first()) // шифротекст есть, но бесполезен: UI попросит ввести ключ заново
    }
}
