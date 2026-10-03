package pro.perfectproduct.cramin.pipeline

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import pro.perfectproduct.cramin.data.db.DocumentEntity
import pro.perfectproduct.cramin.data.db.SourceType

/** Allowlist only: never store/copy exception messages, URLs, document text or provider bodies. */
@Serializable
data class FailureDiagnostic(val source: SourceType, val stage: FailureStage, val code: ErrorCode,
    val rejection: pro.perfectproduct.cramin.llm.RequestRejection? = null,
    val httpStatus: Int? = null, val apiStatus: Int? = null, val observedAtEpochMs: Long? = null,
    val request: pro.perfectproduct.cramin.llm.RequestDiagnostic? = null,
    val local: LocalFailureDiagnostic? = null,
    val origin: DiagnosticOrigin = DiagnosticOrigin.LEGACY_UNKNOWN,
    val recoveredAtEpochMs: Long? = null,
    val recoveryAttemptId: String? = null,
) {
    fun disposition(status: pro.perfectproduct.cramin.data.db.DocStatus): String = when (status) {
        pro.perfectproduct.cramin.data.db.DocStatus.READY -> "RECOVERED_BY_DOCUMENT_READY"
        pro.perfectproduct.cramin.data.db.DocStatus.FAILED -> "DOCUMENT_FAILED"
        else -> "PROCESSING_WITH_HISTORICAL_DIAGNOSTIC"
    }
    fun encode(): String = Json.encodeToString(serializer(), this)
    fun copyText(version: String, api: Int): String =
        "Copied with Cramin: $version\nCopy device Android API: $api\nSource: $source\nStage: $stage\nCode: $code" +
            "\nEvent origin: $origin\nRecovered at: ${recoveredAtEpochMs ?: "UNKNOWN"}\nRecovery attempt: ${recoveryAttemptId ?: "UNKNOWN"}" +
            "\nRejection: ${rejection ?: "UNKNOWN"}\nHTTP: ${httpStatus ?: "UNKNOWN"}\nAPI status: ${apiStatus ?: "UNKNOWN"}" +
            "\nObserved at (epoch ms): ${observedAtEpochMs ?: "UNKNOWN"}" +
            "\nRequest event: ${request?.copyText() ?: "UNKNOWN (not recorded)"}" +
            "\nLocal event: ${local?.copyText() ?: "UNKNOWN (not recorded)"}"
    companion object {
        private val codec = Json { ignoreUnknownKeys = true }
        fun forDocument(doc: DocumentEntity): FailureDiagnostic? = doc.failureJson?.let {
            runCatching { codec.decodeFromString(serializer(), it) }.getOrNull()
        } ?: doc.errorCode?.let { FailureDiagnostic(doc.sourceType, FailureStage.UNKNOWN, ErrorCode.fromName(it)) }
    }
}

@Serializable
enum class FailureStage { CONFIG, FETCHING, LANGUAGE, TRANSCRIBING, BRIEFING, TRANSLATING, EXTRACTING, CONSOLIDATING, UNKNOWN }

@Serializable
enum class DiagnosticOrigin { LEGACY_UNKNOWN, CLIENT_ATTEMPT_ISSUE, TERMINAL_PROCESS_FAILURE }
