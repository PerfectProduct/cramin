package pro.perfectproduct.cramin.pipeline

import pro.perfectproduct.cramin.llm.LlmException

/**
 * Коды ошибок обработки. Хранятся в `Document.errorCode`; русский текст и действие
 * («Повторить», «Открыть настройки», «Выбрать язык») подбирает UI по коду.
 */
enum class ErrorCode {
    NO_KEY,
    AUTH,
    PAYMENT,
    NETWORK,
    RATE_LIMIT,
    SERVER,
    BAD_REQUEST,
    INVALID_RESPONSE,
    LANG_UNDETECTED,
    SAME_LANGUAGE,
    EMPTY_TEXT,
    ARTICLE_EXTRACT,
    PDF_NO_TEXT,
    PDF_INVALID,
    PDF_ENCRYPTED,
    YOUTUBE_FORMAT,
    YOUTUBE_NO_LANG,
    TRANSCRIPTION,
    STORAGE,
    UNKNOWN,
    ;

    companion object {
        fun fromName(name: String?): ErrorCode = name?.let { runCatching { valueOf(it) }.getOrNull() } ?: UNKNOWN
    }
}

/** Ошибка стадии или источника. `message` — техническая, без текстов документа. */
class PipelineException(val code: ErrorCode, message: String, cause: Throwable? = null, val stage: FailureStage? = null, val retryAfterMs: Long? = null) : Exception(message, cause) {
    companion object {
        fun from(t: Throwable): PipelineException = when (t) {
            is PipelineException -> t
            is LlmException.Auth -> PipelineException(if (t.status == 0) ErrorCode.NO_KEY else ErrorCode.AUTH, t.javaClass.simpleName, t)
            is LlmException.Payment -> PipelineException(ErrorCode.PAYMENT, t.javaClass.simpleName, t)
            is LlmException.Network -> PipelineException(ErrorCode.NETWORK, t.javaClass.simpleName, t)
            is LlmException.RateLimited -> PipelineException(ErrorCode.RATE_LIMIT, t.javaClass.simpleName, t)
            is LlmException.Server -> PipelineException(ErrorCode.SERVER, t.javaClass.simpleName, t)
            is LlmException.BadRequest -> PipelineException(ErrorCode.BAD_REQUEST, t.javaClass.simpleName, t)
            is LlmException.InvalidResponse -> PipelineException(ErrorCode.INVALID_RESPONSE, t.javaClass.simpleName, t)
            is LlmException.BudgetExceeded -> PipelineException(ErrorCode.PAYMENT, t.javaClass.simpleName, t)
            is java.io.IOException -> PipelineException(ErrorCode.NETWORK, t.javaClass.simpleName, t)
            else -> PipelineException(ErrorCode.UNKNOWN, "unexpected error", t)
        }
    }
}
