package pro.perfectproduct.cramin.ui.document

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pro.perfectproduct.cramin.R
import pro.perfectproduct.cramin.app.craminViewModel
import pro.perfectproduct.cramin.data.db.DocStatus
import pro.perfectproduct.cramin.data.db.DocumentEntity
import pro.perfectproduct.cramin.pipeline.ErrorCode
import pro.perfectproduct.cramin.ui.components.ErrorAction
import pro.perfectproduct.cramin.ui.components.LangSelector
import pro.perfectproduct.cramin.ui.components.errorActionLabel
import pro.perfectproduct.cramin.ui.components.errorActions
import pro.perfectproduct.cramin.ui.components.errorMessageRes
import pro.perfectproduct.cramin.ui.components.formatUsd
import pro.perfectproduct.cramin.util.Lang

/** Экран документа (SPEC §9.3): шапка, вкладки «Текст» и «Карточки», меню ⋮. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(
    documentId: Long,
    initialTab: String,
    onBack: () -> Unit,
    onStudy: (String, Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    vm: DocumentViewModel = craminViewModel(key = "doc-$documentId") { DocumentViewModel(it, documentId) },
) {
    val row by vm.document.collectAsState()
    val counts by vm.counts.collectAsState()
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var reprocessing by remember { mutableStateOf(false) }
    var chooseLang by remember { mutableStateOf<ErrorAction?>(null) }
    val doc = row?.document

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(doc?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
                actions = {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("docMenu")) { Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_more)) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_rename)) }, onClick = { menuOpen = false; renaming = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_reprocess)) }, onClick = { menuOpen = false; reprocessing = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { menuOpen = false; deleting = true })
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (doc?.costUsd != null) stringResource(R.string.doc_cost, formatUsd(doc.costUsd), modelsSummary(doc)) else stringResource(R.string.doc_cost_unknown),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            onClick = { menuOpen = false },
                        )
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == "text", onClick = { tab = "text" }, icon = { Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_action_document), null) }, label = { Text(stringResource(R.string.doc_tab_text)) }, modifier = Modifier.testTag("tabText"))
                NavigationBarItem(selected = tab == "cards", onClick = { tab = "cards" }, icon = { Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_action_cards), null) }, label = { Text(stringResource(R.string.doc_tab_cards)) }, modifier = Modifier.testTag("tabCards"))
            }
        },
    ) { padding ->
        if (doc == null) return@Scaffold
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            DocumentHeader(doc, counts.total, counts.known)
            StatusBanner(doc, onRetry = vm::retry, onOpenSettings = onOpenSettings, onChooseLang = { chooseLang = it }, onCheckUpdates = onOpenSettings)
            if (tab == "text") {
                TextTab(vm)
            } else {
                CardsTab(vm, counts, onStudy = onStudy)
            }
        }
    }

    if (renaming) {
        var title by remember { mutableStateOf(doc?.title.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.library_rename_title)) },
            text = { OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { vm.rename(title); renaming = false }) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.library_delete_confirm_title)) },
            text = { Text(stringResource(R.string.library_delete_confirm_body, doc?.title.orEmpty())) },
            confirmButton = { TextButton(onClick = { deleting = false; vm.delete(onBack) }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (reprocessing) {
        AlertDialog(
            onDismissRequest = { reprocessing = false },
            title = { Text(stringResource(R.string.library_reprocess_confirm_title)) },
            text = { Text(stringResource(R.string.library_reprocess_confirm_body)) },
            confirmButton = { TextButton(onClick = { reprocessing = false; vm.reprocess() }) { Text(stringResource(R.string.action_reprocess)) } },
            dismissButton = { TextButton(onClick = { reprocessing = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    chooseLang?.let { action ->
        val target = Lang.fromCode(doc?.targetLang)
        val source = Lang.fromCode(doc?.sourceLang)
        AlertDialog(
            onDismissRequest = { chooseLang = null },
            title = { Text(errorActionLabel(action)) },
            text = {
                LangSelector(
                    selected = null,
                    order = listOf(Lang.EN, Lang.RU, Lang.HE),
                    enabled = { lang -> if (action == ErrorAction.CHOOSE_SOURCE_LANG) lang != target else lang != source },
                    onSelect = { lang ->
                        if (action == ErrorAction.CHOOSE_SOURCE_LANG) vm.chooseSourceLang(lang) else vm.chooseTargetLang(lang)
                        chooseLang = null
                    },
                )
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { chooseLang = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private fun modelsSummary(doc: DocumentEntity): String = doc.modelsSnapshotJson?.let { json ->
    Regex("\"model\":\"([^\"]+)\"").findAll(json).map { it.groupValues[1] }.distinct().joinToString(", ")
}?.takeIf { it.isNotEmpty() } ?: "—"

@Composable
private fun DocumentHeader(doc: DocumentEntity, total: Int, known: Int) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(doc.emoji, style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(doc.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val src = Lang.fromCode(doc.sourceLang)?.label ?: "?"
            val tgt = Lang.fromCode(doc.targetLang)?.label ?: "?"
            Text(
                stringResource(R.string.doc_header_stats, total, known, total, src, tgt),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Обработка и ошибки с действием (SPEC: «Ошибка — повторить», конкретные сообщения по коду). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusBanner(doc: DocumentEntity, onRetry: () -> Unit, onOpenSettings: () -> Unit, onChooseLang: (ErrorAction) -> Unit, onCheckUpdates: () -> Unit) {
    if (doc.studyNotice) {
        Text(stringResource(R.string.study_migration_notice), modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium)
    }
    val diagnostic = pro.perfectproduct.cramin.pipeline.FailureDiagnostic.forDocument(doc)
    val context = androidx.compose.ui.platform.LocalContext.current
    if (diagnostic != null) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
            val stageLabel = when (diagnostic.stage) {
                pro.perfectproduct.cramin.pipeline.FailureStage.CONFIG -> stringResource(R.string.diagnostic_config)
                pro.perfectproduct.cramin.pipeline.FailureStage.LANGUAGE -> stringResource(R.string.diagnostic_language)
                pro.perfectproduct.cramin.pipeline.FailureStage.UNKNOWN -> stringResource(R.string.diagnostic_unknown)
                else -> statusLabel(DocStatus.valueOf(diagnostic.stage.name))
            }
            Text(stringResource(R.string.diagnostic_previous, stageLabel, diagnostic.code.name), style = MaterialTheme.typography.bodyMedium)
            if (diagnostic.stage == pro.perfectproduct.cramin.pipeline.FailureStage.UNKNOWN) {
                Text(stringResource(R.string.diagnostic_unknown_stage), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Cramin", diagnostic.copyText(pro.perfectproduct.cramin.BuildConfig.VERSION_NAME, android.os.Build.VERSION.SDK_INT)))
            }) { Text(stringResource(R.string.diagnostic_copy)) }
        }
    }
    when {
        doc.status == DocStatus.FAILED -> {
            val code = ErrorCode.fromName(doc.errorCode)
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("errorBanner"),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(stringResource(errorMessageRes(code)), color = MaterialTheme.colorScheme.onErrorContainer)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        for (action in errorActions(code)) {
                            OutlinedButton(
                                onClick = {
                                    when (action) {
                                        ErrorAction.RETRY -> onRetry()
                                        ErrorAction.OPEN_SETTINGS -> onOpenSettings()
                                        ErrorAction.CHOOSE_SOURCE_LANG, ErrorAction.CHOOSE_TARGET_LANG -> onChooseLang(action)
                                        ErrorAction.CHECK_UPDATES -> onCheckUpdates()
                                    }
                                },
                            ) { Text(errorActionLabel(action)) }
                        }
                    }
                }
            }
        }
        !doc.status.isTerminal -> {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                Text(
                    stringResource(R.string.doc_processing_body, statusLabel(doc.status), (doc.progress * 100).toInt()),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(progress = { doc.progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
        doc.wordCount > BIG_DOCUMENT_WORDS -> Text(
            stringResource(R.string.doc_big_warning, doc.wordCount),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
    }
}

private const val BIG_DOCUMENT_WORDS = 50_000

@Composable
fun statusLabel(status: DocStatus): String = stringResource(
    when (status) {
        DocStatus.QUEUED -> R.string.status_queued
        DocStatus.FETCHING -> R.string.status_fetching
        DocStatus.TRANSCRIBING -> R.string.status_transcribing
        DocStatus.BRIEFING -> R.string.status_briefing
        DocStatus.TRANSLATING -> R.string.status_translating
        DocStatus.EXTRACTING -> R.string.status_extracting
        DocStatus.CONSOLIDATING -> R.string.status_consolidating
        DocStatus.READY -> R.string.status_ready
        DocStatus.FAILED -> R.string.status_failed
    },
)
