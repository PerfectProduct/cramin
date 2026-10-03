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
) {
    fun encode(): String = Json.encodeToString(serializer(), this)
    fun copyText(version: String, api: Int): String =
        "Copied with Cramin: $version\nCopy device Android API: $api\nSource: $source\nStage: $stage\nCode: $code" +
            "\nRejection: ${rejection ?: "UNKNOWN"}\nHTTP: ${httpStatus ?: "UNKNOWN"}\nAPI status: ${apiStatus ?: "UNKNOWN"}" +
            "\nObserved at (epoch ms): ${observedAtEpochMs ?: "UNKNOWN"}" +
            "\nRequest event: ${request?.copyText() ?: "UNKNOWN (not recorded)"}"
    companion object {
        private val codec = Json { ignoreUnknownKeys = true }
        fun forDocument(doc: DocumentEntity): FailureDiagnostic? = doc.failureJson?.let {
            runCatching { codec.decodeFromString(serializer(), it) }.getOrNull()
        } ?: doc.errorCode?.let { FailureDiagnostic(doc.sourceType, FailureStage.UNKNOWN, ErrorCode.fromName(it)) }
    }
}

@Serializable
enum class FailureStage { CONFIG, FETCHING, LANGUAGE, TRANSCRIBING, BRIEFING, TRANSLATING, EXTRACTING, CONSOLIDATING, UNKNOWN }
