package pro.perfectproduct.cramin.pipeline

import kotlinx.serialization.Serializable
import pro.perfectproduct.cramin.BuildConfig
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

@Serializable
enum class ConsolidationStep { LOAD_SNAPSHOT, LOAD_INPUTS, READ_EXTRACTION, VALIDATE_UNITS, MERGE_UNITS,
    BUILD_BATCH, READ_CACHED_RESPONSE, REQUEST, PARSE_RESPONSE, SAVE_RESPONSE, APPLY_SENSES,
    CAPTURE_PROGRESS, REPLACE_CARDS, WRITE_SENSE, WRITE_OCCURRENCES, COMMIT_READY }

@Serializable
enum class SavedBatchState { DONE_JSON, DONE_FALLBACK, MISSING_RESPONSE, UNFINISHED_LENGTH, UNFINISHED_OTHER }

@Serializable
data class LocalFailureDiagnostic(
    val build: String,
    val buildCode: Int,
    val attemptId: String,
    val cacheOnly: Boolean,
    val step: ConsolidationStep,
    val exceptionTypes: List<String>,
    val appFrames: List<String>,
    val counts: Map<String, Int>,
    // Counts cover this CONSOLIDATING attempt, not earlier stages or previous attempts.
    val clientInvocations: Int,
    val clientResponses: Int,
    val savedBatchState: SavedBatchState? = null,
) {
    fun copyText(): String = "Local event build: $build ($buildCode)\nAttempt: $attemptId\nCache only: $cacheOnly" +
        "\nSubstage: $step\nException types: ${exceptionTypes.joinToString(" -> ")}" +
        "\nApplication frames: ${appFrames.joinToString("; ")}" +
        "\nSaved batch state (historical): ${savedBatchState ?: "UNKNOWN"}" +
        "\nStructural counts: ${counts.entries.joinToString { "${it.key}=${it.value}" }}" +
        "\nConsolidation API client: ${if (clientInvocations == 0) "NOT_INVOKED" else "INVOKED (HTTP details only in request event)"}" +
        "\nClient invocations/responses: $clientInvocations/$clientResponses"
}

/** Per-coroutine attempt state; never holds payloads or exception messages. */
internal class ConsolidationTrace(val cacheOnly: Boolean, val consolidationOnly: Boolean = cacheOnly) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ConsolidationTrace>
    var active = false
    var step = ConsolidationStep.LOAD_SNAPSHOT
    var savedBatchState: SavedBatchState? = null
    val counts = linkedMapOf<String, Int>()
    val invocations = AtomicInteger()
    val responses = AtomicInteger()
    var partPath: String? = null
    val attemptId = java.util.UUID.randomUUID().toString()
    fun failure(t: Throwable): LocalFailureDiagnostic {
        val chain = mutableListOf<Throwable>()
        var cause: Throwable? = t
        while (cause != null && chain.none { it === cause } && chain.size < 8) {
            chain += cause
            cause = cause.cause
        }
        fun identifier(s: String) = s.take(180).replace(Regex("[^A-Za-z0-9_.$<>-]"), "_")
        return LocalFailureDiagnostic(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, attemptId, cacheOnly, step,
            chain.map { identifier(it.javaClass.name) },
            chain.flatMap { it.stackTrace.toList() }.filter { it.className.startsWith("pro.perfectproduct.cramin.") }
                .take(16).map { "${identifier(it.className)}.${identifier(it.methodName)}:${it.lineNumber}" },
            counts.toMap(), invocations.get(), responses.get(), savedBatchState)
    }
}

/** No arbitrary message: the type and current step explain missing/incompatible persisted inputs. */
internal class ConsolidationCacheMissing : Exception()
internal class ConsolidationCacheInvalid(cause: Throwable? = null) : Exception(cause)

internal inline fun <T> readConsolidationCache(read: () -> T): T = try { read() } catch (e: kotlinx.serialization.SerializationException) {
    throw ConsolidationCacheInvalid(e)
} catch (e: IllegalArgumentException) {
    throw ConsolidationCacheInvalid(e)
}
