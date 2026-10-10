package pro.perfectproduct.cramin.chatgpt

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.serialization.json.*
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.io.RandomAccessFile
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only ciphertext in noBackupFilesDir: excluded from cloud backup and device transfer on all supported APIs. */
internal interface CredentialStore {
    fun read(): JsonObject
    fun update(block: (JsonObject) -> JsonObject): JsonObject
}
internal class CredentialVault(context: Context) : CredentialStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "chatgpt-prototype-v1.enc"))
    private val lockFile = File(context.noBackupFilesDir, "chatgpt-session.lock")
    private val alias = "cramin.debug.chatgpt.prototype.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    private fun <T> locked(block: () -> T): T = synchronized(PROCESS_LOCK) {
        lockFile.parentFile?.mkdirs()
        RandomAccessFile(lockFile, "rw").use { handle -> handle.channel.lock().use { block() } }
    }
    override fun read(): JsonObject = locked { readUnlocked() }
    /** Covers read, refresh/revocation and atomic replacement across both Android processes. */
    override fun update(block: (JsonObject) -> JsonObject): JsonObject = locked {
        val old = readUnlocked()
        val next = block(old)
        if (old != next) writeUnlocked(next)
        next
    }
    private fun readUnlocked(): JsonObject {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return buildJsonObject {}
        val bytes = file.readFully()
        check(bytes.size >= 29 && bytes[0] == 1.toByte()) { "vault_format" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
            updateAAD(alias.toByteArray())
        }
        return Json.parseToJsonElement(cipher.doFinal(bytes.copyOfRange(13, bytes.size)).toString(Charsets.UTF_8)).jsonObject
    }
    fun write(record: JsonObject) = locked { writeUnlocked(record) }
    private fun writeUnlocked(record: JsonObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key()); updateAAD(alias.toByteArray())
        }
        val bytes = byteArrayOf(1) + cipher.iv + cipher.doFinal(record.toString().toByteArray())
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (t: Throwable) { file.failWrite(stream); throw t }
    }
    fun hostId(): String = update { record ->
        if (record.string("host_id") != null) record else JsonObject(record +
            ("host_id" to JsonPrimitive("urn:uuid:${UUID.randomUUID()}")))
    }.string("host_id")!!
    /** Local disconnect keeps the stable host and issued registration, removes all tokens/identity hints. */
    fun clearTokens() { update(::withoutTokens) }
    companion object {
        private val PROCESS_LOCK = Any()
        internal fun withoutTokens(record: JsonObject) = JsonObject(record.filterKeys {
            it in setOf("host_id", "client_id", "subject", "issuer")
        })
    }
}
