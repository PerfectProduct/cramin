package pro.perfectproduct.cramin.ui.create

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
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

/** Import is staged locally; only the primary action schedules processing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateScreen(shared: SharedInput?, onClose: () -> Unit, onOpenSettings: () -> Unit,
    vm: CreateViewModel = craminViewModel { CreateViewModel(it) }) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    var mode by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(CreateMode.TEXT) }
    var urlText by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var pastedText by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var pastedTitle by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var advanced by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(shared) {
        when {
            shared?.url != null -> { urlText = shared.url; mode = CreateMode.URL }
            shared?.text != null -> { pastedText = shared.text; mode = CreateMode.TEXT }
            shared?.pdfUri != null -> { mode = CreateMode.PDF; vm.pickPdf(context, shared.pdfUri) }
        }
    }
    LaunchedEffect(state.done) { if (state.done) onClose() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.pickPdf(context, it) } }
    val submitTag = when(mode) { CreateMode.TEXT -> "submitText"; CreateMode.URL -> "submitUrl"; CreateMode.PDF -> "submitPdf" }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.create_title_1), style = MaterialTheme.typography.titleLarge) }, navigationIcon = {
        IconButton(onClick = onClose, modifier = Modifier.testTag("createClose")) { Icon(Icons.Default.Close, stringResource(R.string.action_close)) }
    }) }, bottomBar = {
        pro.perfectproduct.cramin.ui.components.ActionDock(Modifier.navigationBarsPadding().imePadding()) {
            pro.perfectproduct.cramin.ui.components.PrimaryAction(stringResource(R.string.create_submit), {
                if (Build.VERSION.SDK_INT >= 33 && !state.notificationsAsked) { vm.markNotificationsAsked(); permission.launch(Manifest.permission.POST_NOTIFICATIONS) }
                when(mode) { CreateMode.TEXT -> vm.submitText(pastedText, pastedTitle); CreateMode.URL -> vm.submitUrl(urlText); CreateMode.PDF -> vm.submitPdf() }
            }, Modifier.testTag(submitTag), state.hasKey && !state.busy && (mode != CreateMode.PDF || state.pdfName != null))
            pro.perfectproduct.cramin.ui.components.StatusText(if (state.textProvider == pro.perfectproduct.cramin.llm.TextProvider.CHATGPT_PLAN)
                "ChatGPT plan · ${state.textModel ?: "модель не выбрана"} · расход лимита или доступных кредитов" else stringResource(R.string.create_cost_short))
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            pro.perfectproduct.cramin.ui.components.SingleChoice(CreateMode.entries, mode, { stringResource(when(it) { CreateMode.TEXT -> R.string.source_text_short; CreateMode.URL -> R.string.source_link_short; CreateMode.PDF -> R.string.source_pdf_short }) }, { mode = it }, tag = { Modifier.testTag(when(it) { CreateMode.TEXT -> "sourceText"; CreateMode.URL -> "sourceUrl"; CreateMode.PDF -> "sourcePdf" }) })
            when(mode) {
                CreateMode.TEXT -> {
                    OutlinedTextField(pastedText, { pastedText = it }, label = { Text(stringResource(R.string.source_text_short)) }, placeholder = { Text(stringResource(R.string.create_text_hint)) }, minLines = 4, maxLines = 8, textStyle = contentTextStyle(MaterialTheme.typography.bodyMedium), modifier = Modifier.fillMaxWidth().testTag("pasteField"))
                    TextButton(onClick = { clipboardText(context)?.let { pastedText = it } }) { Text(stringResource(R.string.create_paste)) }
                    if (state.sourceLangNeeded) {
                        Text(stringResource(R.string.create_lang_unknown))
                        LangSelector(state.sourceLang, vm::setSourceLang)
                    }
                }
                CreateMode.URL -> OutlinedTextField(urlText, { urlText = it }, label = { Text(stringResource(R.string.create_url_dialog_title)) }, placeholder = { Text(stringResource(R.string.create_url_hint)) }, modifier = Modifier.fillMaxWidth().testTag("urlField"))
                CreateMode.PDF -> {
                    state.pdfName?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    androidx.compose.material3.OutlinedButton(onClick = { picker.launch(arrayOf("application/pdf")) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("pickPdf")) { Text(stringResource(R.string.create_pdf)) }
                }
            }
            Text(stringResource(R.string.create_target), style = MaterialTheme.typography.titleMedium)
            LangSelector(state.targetLang, vm::setTargetLang, Modifier.testTag("targetLang"))
            if (!state.hasKey) {
                Column(Modifier.testTag("noKeyBanner")) {
                    pro.perfectproduct.cramin.ui.components.StatusText(stringResource(R.string.provider_not_ready), error = true)
                    TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.action_open_settings)) }
                }
            }
            state.error?.let { pro.perfectproduct.cramin.ui.components.StatusText(it, error = true) }
            pro.perfectproduct.cramin.ui.components.StatusText(stringResource(R.string.create_result))
            TextButton(onClick = { advanced = !advanced }) { Text(stringResource(R.string.create_advanced)) }
            if (advanced) {
                if (mode == CreateMode.TEXT) OutlinedTextField(pastedTitle, { pastedTitle = it }, label = { Text(stringResource(R.string.create_text_name_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                pro.perfectproduct.cramin.ui.components.NavigationRow(stringResource(R.string.settings_processing), stringResource(R.string.create_advanced_help), onClick = onOpenSettings)
            }
        }
    }
}
private enum class CreateMode { TEXT, URL, PDF }

private fun clipboardText(context: Context): String? {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val item = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return null
    return item.coerceToText(context)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
}
