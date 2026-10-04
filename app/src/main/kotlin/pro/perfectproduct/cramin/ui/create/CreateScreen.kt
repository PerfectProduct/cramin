package pro.perfectproduct.cramin.ui.create

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pro.perfectproduct.cramin.R
import pro.perfectproduct.cramin.app.SharedInput
import pro.perfectproduct.cramin.app.craminViewModel
import pro.perfectproduct.cramin.ingest.UrlClassifier
import pro.perfectproduct.cramin.ui.components.LangSelector
import pro.perfectproduct.cramin.ui.components.contentTextStyle
import pro.perfectproduct.cramin.util.Lang

/** Экран загрузки (SPEC §9.2): полноэкранный диалог с выбором языка перевода и источника. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateScreen(
    shared: SharedInput?,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    vm: CreateViewModel = craminViewModel { CreateViewModel(it) },
) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    var mode by remember { mutableStateOf<CreateMode?>(null) }
    var urlText by remember { mutableStateOf("") }
    var pastedText by remember { mutableStateOf("") }
    var pastedTitle by remember { mutableStateOf("") }

    LaunchedEffect(shared) {
        when {
            shared?.url != null -> { urlText = shared.url; mode = CreateMode.URL }
            shared?.text != null -> { pastedText = shared.text; mode = CreateMode.TEXT }
            shared?.pdfUri != null -> vm.pickPdf(context, shared.pdfUri)
        }
    }
    LaunchedEffect(state.done) { if (state.done) onClose() }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.pickPdf(context, it) } }

    fun submit(block: () -> Unit) {
        // POST_NOTIFICATIONS запрашивается при первом импорте (SPEC §6.11).
        if (Build.VERSION.SDK_INT >= 33 && !state.notificationsAsked) {
            vm.markNotificationsAsked()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        block()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onClose, modifier = Modifier.testTag("createClose")) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close)) }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.create_title_1), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.create_result), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.create_api_notice), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(20.dp))
            if (!state.hasKey) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().testTag("noKeyBanner"),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(stringResource(R.string.create_no_key), color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.action_open_settings)) }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            Text(stringResource(R.string.create_target), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            LangSelector(selected = state.targetLang, onSelect = vm::setTargetLang, modifier = Modifier.testTag("targetLang"))
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.create_source_label), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SourcePill(stringResource(R.string.create_pdf), R.drawable.ic_action_document, enabled = state.hasKey, tag = "sourcePdf") { pdfPicker.launch(arrayOf("application/pdf")) }
                SourcePill(stringResource(R.string.create_url), R.drawable.ic_action_link, enabled = state.hasKey, tag = "sourceUrl") {
                    if (urlText.isEmpty()) clipboardText(context)?.let { if (UrlClassifier.isUrl(it)) urlText = it }
                    mode = CreateMode.URL
                }
                SourcePill(stringResource(R.string.create_text), R.drawable.ic_action_clipboard, enabled = state.hasKey, tag = "sourceText") {
                    if (pastedText.isEmpty()) clipboardText(context)?.let { if (!UrlClassifier.isUrl(it)) pastedText = it }
                    mode = CreateMode.TEXT
                }
            }
            state.pdfName?.let { name ->
                Spacer(Modifier.height(20.dp))
                Text("📁 $name", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { submit { vm.submitPdf() } }, enabled = state.hasKey && !state.busy, modifier = Modifier.fillMaxWidth().testTag("submitPdf")) {
                    Text(stringResource(R.string.create_submit))
                }
            }
            var advanced by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
            TextButton(onClick = { advanced = !advanced }) { Text(stringResource(R.string.create_advanced)) }
            if (advanced) {
                Text(stringResource(R.string.create_advanced_help), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.settings_processing)) }
            }
            state.error?.let { Spacer(Modifier.height(12.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(32.dp))
        }
    }

    when (mode) {
        CreateMode.URL -> AlertDialog(
            onDismissRequest = { mode = null },
            title = { Text(stringResource(R.string.create_url_dialog_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.create_api_notice), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = urlText, onValueChange = { urlText = it }, singleLine = true,
                        placeholder = { Text(stringResource(R.string.create_url_hint)) },
                        modifier = Modifier.fillMaxWidth().testTag("urlField"),
                    )
                    if (state.error != null) Text(state.error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = { submit { vm.submitUrl(urlText) } }, enabled = state.hasKey && !state.busy, modifier = Modifier.testTag("submitUrl")) {
                    Text(stringResource(R.string.create_submit))
                }
            },
            dismissButton = { TextButton(onClick = { mode = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
        CreateMode.TEXT -> TextInputDialog(
            text = pastedText, onText = { pastedText = it }, title = pastedTitle, onTitle = { pastedTitle = it },
            enabled = state.hasKey && !state.busy, error = state.error,
            sourceLangNeeded = state.sourceLangNeeded, sourceLang = state.sourceLang, onSourceLang = vm::setSourceLang,
            onSubmit = { submit { vm.submitText(pastedText, pastedTitle) } }, onDismiss = { mode = null },
        )
        null -> Unit
    }
}

private enum class CreateMode { URL, TEXT }

@Composable
private fun SourcePill(label: String, icon: Int, enabled: Boolean, tag: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(tag)) {
        Icon(androidx.compose.ui.res.painterResource(icon), null, modifier = Modifier.padding(end = 12.dp))
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun TextInputDialog(
    text: String, onText: (String) -> Unit, title: String, onTitle: (String) -> Unit,
    enabled: Boolean, error: String?, sourceLangNeeded: Boolean, sourceLang: Lang?, onSourceLang: (Lang) -> Unit,
    onSubmit: () -> Unit, onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.create_text_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.create_api_notice), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = title, onValueChange = onTitle, singleLine = true,
                    placeholder = { Text(stringResource(R.string.create_text_name_hint)) }, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text, onValueChange = onText, minLines = 6,
                    placeholder = { Text(stringResource(R.string.create_text_hint)) },
                    textStyle = contentTextStyle(MaterialTheme.typography.bodyMedium),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).testTag("pasteField"),
                )
                if (sourceLangNeeded) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.create_lang_unknown), style = MaterialTheme.typography.bodySmall)
                    LangSelector(selected = sourceLang, onSelect = onSourceLang, order = listOf(Lang.EN, Lang.RU, Lang.HE))
                }
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = onSubmit, enabled = enabled, modifier = Modifier.testTag("submitText")) { Text(stringResource(R.string.create_submit)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun clipboardText(context: Context): String? {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val item = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return null
    return item.coerceToText(context)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
}
