package pro.perfectproduct.cramin.pipeline

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import pro.perfectproduct.cramin.data.db.DocumentEntity
import pro.perfectproduct.cramin.data.db.SourceType

/** Allowlist only: never store/copy exception messages, URLs, document text or provider bodies. */
@Serializable
data class FailureDiagnostic(val source: SourceType, val stage: FailureStage, val code: ErrorCode) {
    fun encode(): String = Json.encodeToString(serializer(), this)
    fun copyText(version: String, api: Int): String =
        "Cramin $version\nAndroid API: $api\nSource: $source\nStage: $stage\nCode: $code"
    companion object {
        fun forDocument(doc: DocumentEntity): FailureDiagnostic? = doc.failureJson?.let {
            runCatching { Json.decodeFromString(serializer(), it) }.getOrNull()
        } ?: doc.errorCode?.let { FailureDiagnostic(doc.sourceType, FailureStage.UNKNOWN, ErrorCode.fromName(it)) }
    }
}

@Serializable
enum class FailureStage { CONFIG, FETCHING, LANGUAGE, TRANSCRIBING, BRIEFING, TRANSLATING, EXTRACTING, CONSOLIDATING, UNKNOWN }
