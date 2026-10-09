package pro.perfectproduct.cramin.ui.document

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
    androidx.compose.runtime.key(documentId) {
    val row by vm.document.collectAsState()
    val counts by vm.counts.collectAsState()
    var tab by rememberSaveable(documentId) { mutableStateOf(initialTab) }
    var menuOpen by remember { mutableStateOf(false) }
    var diagnosticsOpen by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var reprocessing by remember { mutableStateOf(false) }
    var chooseLang by remember { mutableStateOf<ErrorAction?>(null) }
    val doc = row?.document

    androidx.activity.compose.BackHandler(diagnosticsOpen) { diagnosticsOpen = false }
    val tabState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (diagnosticsOpen) R.string.doc_info_title else R.string.doc_material), style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { IconButton(onClick = { if (diagnosticsOpen) diagnosticsOpen = false else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
                actions = {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("docMenu")) { Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_more)) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(modifier = Modifier.testTag("diagnosticsMenuItem"), text = { Text(stringResource(R.string.doc_diagnostics)) }, onClick = { menuOpen = false; diagnosticsOpen = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_rename)) }, onClick = { menuOpen = false; renaming = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_reprocess)) }, onClick = { menuOpen = false; reprocessing = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { menuOpen = false; deleting = true })

                    }
                },
            )
        },
        bottomBar = {
            if (!diagnosticsOpen) androidx.compose.material3.Surface {
                Column(Modifier.navigationBarsPadding()) {
                    androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().selectableGroup()) {
                        listOf("cards" to R.string.doc_tab_cards, "text" to R.string.doc_tab_text).forEach { (key, label) ->
                            androidx.compose.foundation.layout.Box(Modifier.weight(1f).heightIn(min = 48.dp)
                                .testTag(if (key == "cards") "tabCards" else "tabText")
                                .selectable(tab == key, role = androidx.compose.ui.semantics.Role.Tab, onClick = { tab = key })
                                .padding(horizontal = 12.dp, vertical = 14.dp), contentAlignment = Alignment.Center) {
                                Text(stringResource(label), style = MaterialTheme.typography.titleMedium,
                                    color = if (tab == key) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    textDecoration = if (tab == key) androidx.compose.ui.text.style.TextDecoration.Underline else null)
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (doc == null) return@Scaffold
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            val header: @Composable () -> Unit = {
                DocumentHeader(doc)
                if (diagnosticsOpen || doc.status != DocStatus.READY) StatusBanner(doc, onCopy = vm::copyDiagnostics, onResumeApi = vm::resumeConsolidationWithApi, onRetryLocal = vm::retryLocalConsolidation, onRetry = vm::retry, onOpenSettings = onOpenSettings, onChooseLang = { chooseLang = it }, onCheckUpdates = onOpenSettings, details = diagnosticsOpen, onDetails = { diagnosticsOpen = true })
            }
            if (diagnosticsOpen) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                    header()
                    Text(statusLabel(doc.status), Modifier.padding(horizontal = 20.dp))
                    val requestModels by vm.requestModels.collectAsState()
                    DocumentCosts(doc, requestModels)
                    DocumentDiagnosticExport(vm, doc.status == DocStatus.READY)
                    val summary by androidx.compose.runtime.produceState("", doc.id, doc.updatedAt, doc.status) { value = vm.diagnostics() }
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(summary, Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else tabState.SaveableStateProvider("$documentId:$tab") {
                if (tab == "text") TextTab(vm, header)
                else if (doc.status != DocStatus.READY) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) { header() }
                else CardsTab(vm, counts, onStudy = onStudy, header = header)
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

}

@Composable
private fun DocumentCosts(doc: DocumentEntity, requestModels: List<String>) {
    val snapshotModels = remember(doc.modelsSnapshotJson) {
        doc.modelsSnapshotJson?.let { json ->
            runCatching {
                fun models(element: kotlinx.serialization.json.JsonElement): List<String> = when (element) {
                    is kotlinx.serialization.json.JsonObject -> element.flatMap { (key, value) ->
                        if (key == "model" && value is kotlinx.serialization.json.JsonPrimitive) listOf(value.content)
                        else models(value)
                    }
                    is kotlinx.serialization.json.JsonArray -> element.flatMap { models(it) }
                    else -> emptyList()
                }
                models(kotlinx.serialization.json.Json.parseToJsonElement(json)).distinct()
            }.getOrDefault(emptyList())
        }.orEmpty()
    }
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.doc_cost_group), style = MaterialTheme.typography.titleMedium)
        Text(if (doc.costUsd == null) stringResource(R.string.doc_cost_unknown)
            else stringResource(R.string.doc_cost_amount, formatUsd(doc.costUsd)), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.doc_cost_incomplete), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.doc_models_requested), style = MaterialTheme.typography.titleSmall)
        if (requestModels.isEmpty()) Text(stringResource(R.string.doc_models_missing), style = MaterialTheme.typography.bodyMedium)
        requestModels.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (snapshotModels.isNotEmpty()) {
            Text(stringResource(R.string.doc_models_snapshot), style = MaterialTheme.typography.titleSmall)
            snapshotModels.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun DocumentHeader(doc: DocumentEntity) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(doc.title, style = MaterialTheme.typography.titleLarge)
        val src = Lang.fromCode(doc.sourceLang)?.label ?: "?"
        val tgt = Lang.fromCode(doc.targetLang)?.label ?: "?"
        Text(
            doc.emoji + " " + stringResource(R.string.doc_source_languages, sourceTypeLabel(doc.sourceType), src, tgt),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Обработка и ошибки с действием (SPEC: «Ошибка — повторить», конкретные сообщения по коду). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusBanner(doc: DocumentEntity, onCopy: (android.content.Context) -> Unit, onResumeApi: () -> Unit, onRetryLocal: () -> Unit, onRetry: () -> Unit, onOpenSettings: () -> Unit, onChooseLang: (ErrorAction) -> Unit, onCheckUpdates: () -> Unit, details: Boolean = false, onDetails: () -> Unit = {}) {
    val oldParameters = doc.modelsSnapshotJson?.let {
        runCatching { pro.perfectproduct.cramin.llm.ProcessingSnapshot.decode(it).legacyParametersUnknown }.getOrDefault(false)
    } == true
    var confirmApi by remember { mutableStateOf(false) }
    if (confirmApi) AlertDialog(
        onDismissRequest = { confirmApi = false },
        title = { Text(stringResource(R.string.consolidation_api_resume)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.consolidation_api_confirm))
            if (oldParameters) Text(stringResource(R.string.config_legacy_notice), Modifier.padding(top = 12.dp))
        } },
        confirmButton = { TextButton(onClick = { confirmApi = false; onResumeApi() }) { Text(stringResource(R.string.consolidation_api_start)) } },
        dismissButton = { TextButton(onClick = { confirmApi = false }) { Text(stringResource(R.string.consolidation_api_cancel)) } },
    )

    if (details && oldParameters) Text(stringResource(R.string.config_legacy_notice), modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium)
    if (details && doc.studyNotice) {
        Text(stringResource(R.string.study_migration_notice), modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium)
    }
    val diagnostic = pro.perfectproduct.cramin.pipeline.FailureDiagnostic.forDocument(doc)
    val context = androidx.compose.ui.platform.LocalContext.current
    var showHistory by remember(doc.id, doc.status) { mutableStateOf(doc.status == DocStatus.FAILED) }
    if (details && diagnostic != null) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
            Text(stringResource(when (doc.status) {
                DocStatus.READY -> R.string.diagnostic_recovered
                DocStatus.FAILED -> R.string.diagnostic_failed_state
                else -> R.string.diagnostic_processing_state
            }), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { showHistory = !showHistory }) { Text(stringResource(R.string.diagnostic_history)) }
            if (showHistory) {
            val stageLabel = when (diagnostic.stage) {
                pro.perfectproduct.cramin.pipeline.FailureStage.CONFIG -> stringResource(R.string.diagnostic_config)
                pro.perfectproduct.cramin.pipeline.FailureStage.LANGUAGE -> stringResource(R.string.diagnostic_language)
                pro.perfectproduct.cramin.pipeline.FailureStage.UNKNOWN -> stringResource(R.string.diagnostic_unknown)
                else -> statusLabel(DocStatus.valueOf(diagnostic.stage.name))
            }
            Text(stringResource(R.string.diagnostic_previous, stageLabel, diagnostic.code.name), style = MaterialTheme.typography.bodyMedium)
            diagnostic.rejection?.let { Text(stringResource(R.string.diagnostic_rejection, it.name), style = MaterialTheme.typography.bodySmall) }
            Text(stringResource(R.string.diagnostic_observed,
                diagnostic.observedAtEpochMs?.let { java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)) }
                    ?: stringResource(R.string.diagnostic_unknown)), style = MaterialTheme.typography.bodySmall)
            if (diagnostic.stage == pro.perfectproduct.cramin.pipeline.FailureStage.UNKNOWN) {
                Text(stringResource(R.string.diagnostic_unknown_stage), style = MaterialTheme.typography.bodySmall)
            }
            }
        }
    }
    if (details) TextButton(onClick = { onCopy(context) }, modifier = Modifier.padding(horizontal = 12.dp)) {
        Text(stringResource(R.string.diagnostic_copy))
    }
    when {
        doc.status == DocStatus.FAILED -> {
            val code = ErrorCode.fromName(doc.errorCode)
            androidx.compose.material3.Surface(
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("errorBanner"),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    val effectiveCode = if (code == ErrorCode.UNKNOWN && diagnostic?.local?.exceptionTypes?.any { it.endsWith(".ConsolidationCacheMissing") } == true)
                        if (diagnostic.local?.step == pro.perfectproduct.cramin.pipeline.ConsolidationStep.REQUEST)
                            ErrorCode.CONSOLIDATION_CACHE_UNFINISHED else ErrorCode.CONSOLIDATION_CACHE_MISSING
                    else code
                    Text(stringResource(errorMessageRes(effectiveCode)), color = MaterialTheme.colorScheme.onErrorContainer)
                    if (diagnostic?.stage == pro.perfectproduct.cramin.pipeline.FailureStage.CONSOLIDATING) {
                        if (details) Text(stringResource(R.string.consolidation_local_hint), style = MaterialTheme.typography.bodyMedium)
                        if (details || effectiveCode != ErrorCode.CONSOLIDATION_CACHE_UNFINISHED) OutlinedButton(onClick = onRetryLocal, modifier = Modifier.testTag("retryLocalConsolidation")) {
                            Text(stringResource(R.string.consolidation_local_retry))
                        }
                        if ((details || effectiveCode == ErrorCode.CONSOLIDATION_CACHE_UNFINISHED) && effectiveCode !in listOf(ErrorCode.CONSOLIDATION_CACHE_MISSING, ErrorCode.CONSOLIDATION_CACHE_INVALID, ErrorCode.CONSOLIDATION_LIMIT)) {
                            OutlinedButton(onClick = { confirmApi = true }, modifier = Modifier.testTag("resumeConsolidationApi")) {
                                Text(stringResource(R.string.consolidation_api_resume))
                            }
                        }
                    }
                    if (!details) TextButton(onClick = onDetails) { Text(stringResource(R.string.doc_diagnostics)) }
                    if (diagnostic?.stage != pro.perfectproduct.cramin.pipeline.FailureStage.CONSOLIDATING && errorActions(code).any { it == ErrorAction.RETRY || it == ErrorAction.CHOOSE_SOURCE_LANG || it == ErrorAction.CHOOSE_TARGET_LANG }) {
                        Text(stringResource(R.string.retry_api_notice), style = MaterialTheme.typography.bodySmall)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        for (action in errorActions(code).filterNot {
                            it == ErrorAction.RETRY && diagnostic?.stage == pro.perfectproduct.cramin.pipeline.FailureStage.CONSOLIDATING
                        }.let { if (details) it else it.take(1) }) {
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
        details && doc.wordCount > BIG_DOCUMENT_WORDS -> Text(
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

@Composable
fun sourceTypeLabel(type: pro.perfectproduct.cramin.data.db.SourceType): String = stringResource(when (type) {
    pro.perfectproduct.cramin.data.db.SourceType.TEXT -> R.string.source_text
    pro.perfectproduct.cramin.data.db.SourceType.URL -> R.string.source_article
    pro.perfectproduct.cramin.data.db.SourceType.YOUTUBE -> R.string.source_youtube
    pro.perfectproduct.cramin.data.db.SourceType.PDF -> R.string.source_pdf
})
