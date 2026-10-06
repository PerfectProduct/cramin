package pro.perfectproduct.cramin.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import pro.perfectproduct.cramin.util.Hashing
import java.io.File
import java.io.FileOutputStream

/** Verified APK and durable intent. Installer callbacks update it before Activity can resume. */
class PendingUpdateStore(private val record: File, private val apkDir: File) {
    enum class Phase { PREPARED, PERMISSION, SUBMITTED, RETRY, CANCELLED, FAILED, SUCCEEDED }
    data class Entry(val apk: File, val phase: Phase, val sessionId: Int?)
    // Old records have no intent: require explicit confirmation rather than silently installing.
    @Serializable private data class Pending(val name: String, val sha256: String, val phase: Phase = Phase.RETRY, val sessionId: Int? = null)

    fun save(apk: File) = synchronized(lock) {
        require(apk.isFile && apk.canonicalFile.parentFile == apkDir.canonicalFile)
        write(Pending(apk.name, Hashing.sha256Hex(apk), Phase.PREPARED))
    }

    fun load(): File? = loadEntry()?.takeUnless { it.phase == Phase.SUCCEEDED }?.apk
    fun loadEntry(): Entry? = synchronized(lock) {
        read()?.let { Entry(File(apkDir, it.name), it.phase, it.sessionId) }
    }

    fun transition(phase: Phase, sessionId: Int? = null) = synchronized(lock) {
        val p = read() ?: error("verified update missing")
        write(p.copy(phase = phase, sessionId = sessionId))
    }

    /** Ignore late callbacks from an abandoned/replaced session. No Activity/manager is required. */
    fun completeSession(sessionId: Int, success: Boolean, cancelled: Boolean = false): Boolean = synchronized(lock) {
        val p = read() ?: return false
        if (p.sessionId != sessionId || p.phase != Phase.SUBMITTED) return false
        // Keep successful identity until consumed: clearing here would make a queued Success
        // indistinguishable from success for an APK prepared by a later attempt.
        write(p.copy(phase = if (success) Phase.SUCCEEDED else if (cancelled) Phase.CANCELLED else Phase.FAILED))
        true
    }

    /** Identity remains locked through UI application, not just before a suspension.
     * Receiver already persisted the outcome; never overwrite another attempt's phase here.
     * apply must be synchronous. Lock order in the manager: operationLock -> store lock.
     */
    fun applyTerminalResult(sessionId: Int, phase: Phase, apply: () -> Unit): Boolean = synchronized(lock) {
        require(phase == Phase.CANCELLED || phase == Phase.FAILED || phase == Phase.SUCCEEDED)
        // Reject identity before integrity cleanup too: even a corrupt later APK belongs
        // to that later attempt, not to this queued event.
        val p = readMetadata() ?: return false
        if (p.sessionId != sessionId || p.phase != phase) return false
        if (read() == null) return false
        if (phase == Phase.SUCCEEDED) clear()
        apply()
        true
    }

    fun matchesSession(sessionId: Int): Boolean = synchronized(lock) {
        read()?.let { it.sessionId == sessionId && it.phase == Phase.SUBMITTED } == true
    }

    private fun read(): Pending? {
        if (!record.isFile) return null
        val p = runCatching {
            requireNotNull(readMetadata()).also {
                require(it.name == File(it.name).name && it.name.endsWith(".apk"))
                val apk = File(apkDir, it.name)
                require(apk.isFile && apk.canonicalFile.parentFile == apkDir.canonicalFile && Hashing.sha256Hex(apk) == it.sha256)
            }
        }.getOrNull()
        if (p == null) clear()
        return p
    }

    private fun readMetadata(): Pending? = runCatching {
        Json.decodeFromString<Pending>(record.readText())
    }.getOrNull()

    private fun write(p: Pending) {
        record.parentFile?.mkdirs()
        val tmp = File(record.path + ".tmp")
        try {
            FileOutputStream(tmp).use { it.write(Json.encodeToString(p).toByteArray()); it.fd.sync() }
            check(tmp.renameTo(record)) { "pending update persistence failed" }
        } finally { tmp.delete() }
    }

    fun clear() = synchronized(lock) { record.delete(); File(record.path + ".tmp").delete(); Unit }

    companion object {
        // Receiver and container may own different store instances for the same file.
        private val lock = Any()
        fun forContext(context: android.content.Context) = PendingUpdateStore(
            File(context.filesDir, "pending-update.json"), File(context.cacheDir, "updates"))
    }
}
