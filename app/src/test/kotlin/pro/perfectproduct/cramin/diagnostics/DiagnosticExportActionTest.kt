package pro.perfectproduct.cramin.diagnostics

import android.net.Uri
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DiagnosticExportActionTest {
    @Test fun cancellingPickerNeverReadsOrWritesAndCanTryAgain() = runTest {
        var writes = 0
        val action = DiagnosticExportAction(this) { writes++ }
        assertTrue(action.requestPicker()); assertFalse(action.requestPicker())
        action.result(null); runCurrent()
        assertEquals(0, writes); assertEquals(ExportState.CANCELLED, action.state.value)
        action.result(Uri.parse("content://fixture/stale-result")); runCurrent()
        assertEquals(0, writes)
        assertTrue(action.requestPicker())
    }

    @Test fun duplicateResultsCannotStartParallelExportAndRestoredResultWorks() = runTest {
        val barrier = CompletableDeferred<Unit>(); var writes = 0
        val action = DiagnosticExportAction(this) { writes++; barrier.await() }
        val uri = Uri.parse("content://fixture/document/1")
        // New ViewModel after process recreation need not remember PICKING.
        action.result(uri); action.result(uri); action.result(null)
        assertEquals(ExportState.WRITING, action.state.value); assertFalse(action.requestPicker())
        runCurrent(); assertEquals(1, writes)
        barrier.complete(Unit); runCurrent(); assertEquals(ExportState.SAVED, action.state.value)
        action.result(uri); runCurrent(); assertEquals(1, writes)
    }

    @Test fun providerFailureIsVisibleAndRetryStartsFreshAttempt() = runTest {
        var writes = 0
        val action = DiagnosticExportAction(this) { if (++writes == 1) throw IOException("Provider detail must not reach UI") }
        val uri = Uri.parse("content://fixture/document/1")
        action.requestPicker(); action.result(uri); runCurrent()
        assertEquals(ExportState.FAILED, action.state.value)
        assertTrue(action.requestPicker()); action.result(uri); runCurrent()
        assertEquals(2, writes); assertEquals(ExportState.SAVED, action.state.value)
    }

    @Test fun pickerLaunchFailureAllowsRetry() = runTest {
        val action = DiagnosticExportAction(this) { fail("No document picked") }
        action.requestPicker(); action.pickerFailed()
        assertEquals(ExportState.FAILED, action.state.value); assertTrue(action.requestPicker())
    }
}
