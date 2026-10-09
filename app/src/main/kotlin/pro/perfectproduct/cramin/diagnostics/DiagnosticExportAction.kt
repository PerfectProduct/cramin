package pro.perfectproduct.cramin.diagnostics

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class ExportState {
    IDLE, PICKING, WRITING, SAVED, CANCELLED, FAILED;
    val busy: Boolean get() = this == PICKING || this == WRITING
}

/** UI/picker coordination; a restored ActivityResult can arrive in a fresh IDLE instance. */
class DiagnosticExportAction(private val scope: CoroutineScope, private val write: suspend (Uri) -> Unit) {
    private val mutable = MutableStateFlow(ExportState.IDLE)
    val state = mutable.asStateFlow()
    fun requestPicker(): Boolean {
        val before = mutable.value
        return !before.busy && mutable.compareAndSet(before, ExportState.PICKING)
    }
    fun pickerFailed() { mutable.compareAndSet(ExportState.PICKING, ExportState.FAILED) }
    fun result(uri: Uri?) {
        val before = mutable.value
        if (before != ExportState.PICKING && before != ExportState.IDLE) return
        if (uri == null) { mutable.compareAndSet(before, ExportState.CANCELLED); return }
        if (!mutable.compareAndSet(before, ExportState.WRITING)) return
        scope.launch {
            try { write(uri); mutable.value = ExportState.SAVED }
            catch (e: CancellationException) { mutable.value = ExportState.IDLE; throw e }
            catch (_: Exception) { mutable.value = ExportState.FAILED }
        }
    }
}
