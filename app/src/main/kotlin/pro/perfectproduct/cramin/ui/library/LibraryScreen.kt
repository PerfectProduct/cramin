package pro.perfectproduct.cramin.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import pro.perfectproduct.cramin.data.db.DocumentWithCounts
import pro.perfectproduct.cramin.ui.components.*
import pro.perfectproduct.cramin.ui.components.EmptyState
import pro.perfectproduct.cramin.ui.components.formatDate

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(
    onOpenDocument: (Long, String) -> Unit,
    onCreate: () -> Unit,
    onSettings: () -> Unit,
    onAllDeck: () -> Unit,
    vm: LibraryViewModel = craminViewModel { LibraryViewModel(it) },
) {
    val items by vm.items.collectAsState()
    val filter by vm.filter.collectAsState()
    val query by vm.query.collectAsState()
    val hasReady by vm.hasReady.collectAsState()
    // A ready row can arrive before the independent ready-count flow.
    // Include it immediately so inserting the deck entry cannot shift the first visible item.
    val showAllDeck = hasReady || items.any { it.document.status == DocStatus.READY }
    var searchOpen by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<DocumentWithCounts?>(null) }
    var renameFor by remember { mutableStateOf<DocumentWithCounts?>(null) }
    var deleteFor by remember { mutableStateOf<DocumentWithCounts?>(null) }
    var reprocessFor by remember { mutableStateOf<DocumentWithCounts?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searchOpen) {
                        OutlinedTextField(
                            value = query, onValueChange = vm::setQuery, singleLine = true,
                            placeholder = { Text(stringResource(R.string.library_search)) },
                            modifier = Modifier.fillMaxWidth().testTag("librarySearch"),
                        )
                    } else {
                        Text(stringResource(R.string.library_title), style = MaterialTheme.typography.headlineSmall)
                    }
                },
                actions = {
                    IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) vm.setQuery("") }) {
                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.library_search))
                    }
                    IconButton(onClick = onSettings, modifier = Modifier.testTag("librarySettings")) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.library_settings))
                    }
                },
            )
        },
        bottomBar = {
            ActionDock(Modifier.navigationBarsPadding()) {
                PrimaryAction(stringResource(R.string.library_create), onCreate, Modifier.testTag("libraryCreate"))
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            SingleChoice(LibraryFilter.entries, filter, { stringResource(it.labelRes) }, vm::setFilter, Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            if (items.isEmpty() && !showAllDeck) {
                EmptyState("📚", stringResource(if (filter == LibraryFilter.ALL && query.isEmpty()) R.string.library_empty else R.string.library_empty_filtered), Modifier.padding(top = 48.dp))
            } else {
                LazyColumn(modifier = Modifier.testTag("libraryList"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    if (showAllDeck) {
                        item(key = "all-unlearned") { AllUnlearnedRow(onClick = onAllDeck) }
                    }
                    if (items.isEmpty()) {
                        item { EmptyState("🔎", stringResource(R.string.library_empty_filtered)) }
                    }
                    items(items, key = { it.document.id }) { row ->
                        DocumentRow(
                            row = row,
                            onClick = {
                                onOpenDocument(row.document.id, "cards")
                            },
                            onLongClick = { menuFor = row },
                            menuOpen = menuFor?.document?.id == row.document.id,
                            onDismissMenu = { menuFor = null },
                            onRename = { renameFor = row; menuFor = null },
                            onReprocess = { reprocessFor = row; menuFor = null },
                            onDelete = { deleteFor = row; menuFor = null },
                        )
                    }
                }
            }
        }
    }

    renameFor?.let { row ->
        var title by remember(row) { mutableStateOf(row.document.title) }
        AlertDialog(
            onDismissRequest = { renameFor = null },
            title = { Text(stringResource(R.string.library_rename_title)) },
            text = { OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { vm.rename(row.document.id, title); renameFor = null }) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = { renameFor = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    deleteFor?.let { row ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text(stringResource(R.string.library_delete_confirm_title)) },
            text = { Text(stringResource(R.string.library_delete_confirm_body, row.document.title)) },
            confirmButton = { TextButton(onClick = { vm.delete(row.document.id); deleteFor = null }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    reprocessFor?.let { row ->
        AlertDialog(
            onDismissRequest = { reprocessFor = null },
            title = { Text(stringResource(R.string.library_reprocess_confirm_title)) },
            text = { Text(stringResource(R.string.library_reprocess_confirm_body)) },
            confirmButton = { TextButton(onClick = { vm.reprocess(row.document.id); reprocessFor = null }) { Text(stringResource(R.string.action_reprocess)) } },
            dismissButton = { TextButton(onClick = { reprocessFor = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun AllUnlearnedRow(onClick: () -> Unit) {
    NavigationRow(stringResource(R.string.library_all_unlearned), null, modifier = Modifier.testTag("allUnlearned"), onClick = onClick)
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun DocumentRow(
    row: DocumentWithCounts,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    menuOpen: Boolean,
    onDismissMenu: () -> Unit,
    onRename: () -> Unit,
    onReprocess: () -> Unit,
    onDelete: () -> Unit,
) {
    val doc = row.document
    val processing = !doc.status.isTerminal
    Column(Modifier.fillMaxWidth().testTag("doc-${doc.id}").combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(doc.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
                    StatusText("${doc.emoji} ${pro.perfectproduct.cramin.ui.document.sourceTypeLabel(doc.sourceType)}")
                    StatusText(formatDate(doc.createdAt).replace(' ', '\u00a0'))
                }
                when {
                    doc.status == DocStatus.FAILED -> StatusText(stringResource(R.string.library_error), error = true)
                    processing -> {
                        StatusText(if (doc.status == DocStatus.QUEUED) stringResource(R.string.library_queued) else stringResource(R.string.library_processing, (doc.progress * 100).toInt()))
                        LinearProgressIndicator(progress = { doc.progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp))
                    }
                    else -> StatusText(stringResource(R.string.library_progress_short, row.cardCount, row.knownCount))
                }
            }
            Box {
                DropdownMenu(expanded = menuOpen, onDismissRequest = onDismissMenu) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_rename)) }, onClick = onRename)
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_reprocess)) }, onClick = onReprocess)
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = onDelete)
                }
            }
        }
        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
