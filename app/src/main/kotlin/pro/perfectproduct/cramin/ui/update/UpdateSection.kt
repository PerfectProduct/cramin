package pro.perfectproduct.cramin.ui.update

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pro.perfectproduct.cramin.R
import pro.perfectproduct.cramin.app.LocalContainer
import pro.perfectproduct.cramin.update.ApkInstaller
import pro.perfectproduct.cramin.update.InstallResult
import pro.perfectproduct.cramin.update.UpdateUi

/** «Проверить обновления» и диалоги обновления (SPEC U8, §12.4). */
@Composable
fun UpdateSection() {
    val container = LocalContainer.current
    val manager = container.updateManager
    val state by manager.state.collectAsState()
    val installResult by ApkInstaller.resultFlow.collectAsState()
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { manager.resumePending() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, manager) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) manager.resumePending() }
        lifecycle.addObserver(observer)
        manager.resumePending()
        onDispose { lifecycle.removeObserver(observer) }
    }

    TextButton(onClick = { manager.check() }, modifier = Modifier.testTag("checkUpdates")) { Text(stringResource(R.string.action_check_updates)) }
    when (val s = state) {
        UpdateUi.Idle -> Unit
        UpdateUi.Checking -> Text(stringResource(R.string.update_checking), style = MaterialTheme.typography.bodySmall)
        is UpdateUi.UpToDate -> Text(stringResource(R.string.update_latest), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        is UpdateUi.Available -> AlertDialog(
            onDismissRequest = { manager.reset() },
            title = { Text(stringResource(R.string.update_available_title, s.release.versionName)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.update_size, formatSize(s.release.apkSize)), style = MaterialTheme.typography.bodySmall)
                    Text(s.release.notes.ifBlank { "—" }, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { manager.downloadAndInstall(s.release) }) { Text(stringResource(R.string.update_install)) } },
            dismissButton = { TextButton(onClick = { manager.reset() }) { Text(stringResource(R.string.action_cancel)) } },
        )
        is UpdateUi.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.update_downloading, if (s.total > 0) (s.done * 100 / s.total).toInt() else 0)) },
            text = { LinearProgressIndicator(progress = { if (s.total > 0) s.done.toFloat() / s.total else 0f }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { manager.cancel() }) { Text(stringResource(R.string.action_cancel)) } },
        )
        is UpdateUi.NeedsPermission -> AlertDialog(
            onDismissRequest = { manager.reset() },
            title = { Text(stringResource(R.string.update_install)) },
            text = { Text(stringResource(R.string.update_permission_body)) },
            confirmButton = { TextButton(onClick = { permission.launch(container.apkInstaller.unknownSourcesIntent()) }) { Text(stringResource(R.string.update_permission_open)) } },
            dismissButton = { TextButton(onClick = { manager.install(s.apk) }) { Text(stringResource(R.string.action_retry)) } },
        )
        is UpdateUi.Installing -> {
            val r = installResult
            Text(
                when (r) {
                    is InstallResult.Failure -> stringResource(R.string.update_install_failed, r.message)
                    else -> stringResource(R.string.update_checking)
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        is UpdateUi.Error -> Column {
            Text(if (s.checksum) stringResource(R.string.update_verify_failed) else stringResource(R.string.update_error, s.message),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = manager::resumePending) { Text(stringResource(R.string.action_retry)) }
        }
    }
}

private fun formatSize(bytes: Long): String = if (bytes <= 0) "?" else "%.1f МБ".format(bytes / 1024.0 / 1024.0)
