package pro.perfectproduct.cramin.ui.document

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pro.perfectproduct.cramin.R
import pro.perfectproduct.cramin.diagnostics.ExportState

@Composable
fun DocumentDiagnosticExport(vm: DocumentViewModel, ready: Boolean) {
    val state by vm.diagnosticExport.state.collectAsState()
    var warning by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) {
        vm.diagnosticExport.result(it)
    }
    Column(Modifier.padding(horizontal = 20.dp)) {
        OutlinedButton(onClick = { warning = true }, enabled = ready && !state.busy,
            modifier = Modifier.testTag("diagnosticExport")) { Text(stringResource(R.string.diagnostic_export)) }
        val message = when (state) {
            ExportState.WRITING -> R.string.diagnostic_export_writing
            ExportState.SAVED -> R.string.diagnostic_export_saved
            ExportState.CANCELLED -> R.string.diagnostic_export_cancelled
            ExportState.FAILED -> R.string.diagnostic_export_failed
            else -> null
        }
        if (message != null) Text(stringResource(message), Modifier.testTag("diagnosticExportResult"))
    }
    if (warning) AlertDialog(
        onDismissRequest = { warning = false },
        title = { Text(stringResource(R.string.diagnostic_export)) },
        text = { Text(stringResource(R.string.diagnostic_export_warning)) },
        confirmButton = { TextButton(onClick = {
            warning = false
            if (vm.diagnosticExport.requestPicker()) try {
                picker.launch("cramin-document-${vm.documentId}-diagnostics.zip")
            } catch (_: Exception) { vm.diagnosticExport.pickerFailed() }
        }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = { warning = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
}
