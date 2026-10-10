package pro.perfectproduct.cramin.chatgpt

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import pro.perfectproduct.cramin.llm.*

/** Route only from the immutable request/snapshot, never from mutable application settings. */
internal class ProviderLlmClient(private val openRouter: LlmClient, private val sessions: ChatGptSessionManager,
    private val responseClient: (suspend () -> AccessSession, AccountModel) -> LlmClient = { session, model ->
        ResponsesLlmClient(session, model, http = sessions.inferenceHttp)
    }) : LlmClient {
    override suspend fun complete(request: LlmRequest): LlmResponse {
        if (request.provider == TextProvider.OPENROUTER) return openRouter.complete(request)
        check(request.role.isText) { "chatgpt_audio_unsupported" }
        try {
            val model = sessions.catalog().firstOrNull { it.slug == request.model }
                ?: throw LlmException.ChatGpt(ChatGptFailure.MODEL)
            var active = sessions.session()
            val client = responseClient({ sessions.session().also { active = it } }, model)
            try {
                return try { client.complete(request) } catch (e: ResponseFailure) {
                    if (e.httpStatus != 401) throw e
                    // Retry only an explicit pre-inference authentication rejection, once.
                    active = sessions.session(active.accessToken)
                    client.complete(request)
                }
            }
            catch (e: ResponseFailure) {
                val failure: LlmException = when {
                    e.kind == ResponseFailureKind.USAGE_LIMIT || e.httpStatus == 429 -> LlmException.ChatGpt(ChatGptFailure.LIMIT)
                    e.kind == ResponseFailureKind.USAGE_UNAVAILABLE -> LlmException.ChatGpt(ChatGptFailure.UNAVAILABLE)
                    e.httpStatus == 401 -> LlmException.ChatGpt(ChatGptFailure.SIGN_IN)
                    e.httpStatus == 404 -> LlmException.ChatGpt(ChatGptFailure.MODEL)
                    e.httpStatus != null && (e.httpStatus >= 500 || e.httpStatus == 403) -> LlmException.ChatGpt(ChatGptFailure.UNAVAILABLE)
                    e.kind == ResponseFailureKind.INTERRUPTED -> LlmException.Network("ChatGPT stream interrupted")
                    else -> LlmException.InvalidResponse(e.diagnostic?.stage?.name ?: e.kind.name)
                }
                val event = RequestDiagnostic.capture(request, ResponsesLlmClient.buildBody(request, model)).copy(
                    provider = "ChatGPT plan", respondedAtEpochMs = System.currentTimeMillis(), httpStatus = e.httpStatus,
                    responseEvidence = e.diagnostic?.toJson(), observation = "CHATGPT_RESPONSE_REJECTED")
                failure.diagnostic = event
                request.onFailureDiagnostic?.invoke(event)
                throw failure
            }
        } catch (e: CancellationException) { throw e }
        catch (e: LlmException) { throw e }
        catch (e: ResponseFailure) { throw LlmException.ChatGpt(when(e.httpStatus) {
            401 -> ChatGptFailure.SIGN_IN
            404 -> ChatGptFailure.MODEL
            429 -> ChatGptFailure.LIMIT
            else -> ChatGptFailure.UNAVAILABLE
        }) }
        catch (_: IllegalArgumentException) { throw LlmException.ChatGpt(ChatGptFailure.UNAVAILABLE) }
        catch (_: java.io.IOException) { throw LlmException.Network("ChatGPT network unavailable") }
        catch (e: IllegalStateException) {
            throw LlmException.ChatGpt(if (e.message in setOf("access_token_missing","session_revoked","plan_permission_missing","access_token_expired"))
                ChatGptFailure.SIGN_IN else ChatGptFailure.UNAVAILABLE)
        }
    }
}
