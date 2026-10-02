package pro.perfectproduct.cramin.util

import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.Response
import kotlin.coroutines.coroutineContext

/** Keeps the cancellation bridge alive until the response body is consumed and closed. */
suspend inline fun <T> Call.useCancellable(block: (Response) -> T): T =
    useWithJob(coroutineContext[Job], block)

/** Also usable by synchronous NewPipe callbacks inside an explicitly bound coroutine job. */
inline fun <T> Call.useWithJob(parent: Job?, block: (Response) -> T): T {
    parent?.ensureActive()
    val call = this
    val watcher = parent?.let {
        CoroutineScope(it + Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
    }
    try {
        return execute().use { response ->
            parent?.ensureActive()
            block(response).also { parent?.ensureActive() }
        }
    } catch (t: Throwable) {
        // A cancelled socket commonly reports IOException; never turn cancellation into retry.
        parent?.ensureActive()
        throw t
    } finally {
        watcher?.cancel()
    }
}
