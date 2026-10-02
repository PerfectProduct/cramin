package pro.perfectproduct.cramin.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import pro.perfectproduct.cramin.ui.components.EmojiBadge
import pro.perfectproduct.cramin.ui.components.EmptyState
import pro.perfectproduct.cramin.ui.components.formatDate

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onCreate, modifier = Modifier.testTag("libraryCreate")) {
                Text(stringResource(R.string.library_create), style = MaterialTheme.typography.titleMedium)
            }
        },
        floatingActionButtonPosition = androidx.compose.material3.FabPosition.Center,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (f in LibraryFilter.entries) {
                    FilterChip(selected = filter == f, onClick = { vm.setFilter(f) }, label = { Text(stringResource(f.labelRes)) })
                }
            }
            if (items.isEmpty() && !hasReady) {
                EmptyState("📚", stringResource(if (filter == LibraryFilter.ALL && query.isEmpty()) R.string.library_empty else R.string.library_empty_filtered), Modifier.padding(top = 48.dp))
            } else {
                LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (hasReady && filter == LibraryFilter.ALL && query.isEmpty()) {
                        item(key = "all-unlearned") { AllUnlearnedRow(onClick = onAllDeck) }
                    }
                    if (items.isEmpty()) {
                        item { EmptyState("🔎", stringResource(R.string.library_empty_filtered)) }
                    }
                    items(items, key = { it.document.id }) { row ->
                        DocumentRow(
                            row = row,
                            onClick = {
                                onOpenDocument(row.document.id, "text")
                            },
                            onPlay = { onOpenDocument(row.document.id, "cards") },
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
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth().testTag("allUnlearned"),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.library_all_unlearned), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(stringResource(R.string.library_all_unlearned_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
            }
            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocumentRow(
    row: DocumentWithCounts,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onLongClick: () -> Unit,
    menuOpen: Boolean,
    onDismissMenu: () -> Unit,
    onRename: () -> Unit,
    onReprocess: () -> Unit,
    onDelete: () -> Unit,
) {
    val doc = row.document
    val processing = !doc.status.isTerminal
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().testTag("doc-${doc.id}").combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            EmojiBadge(doc.emoji)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(doc.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                when {
                    doc.status == DocStatus.FAILED -> Text(stringResource(R.string.library_error), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    processing -> {
                        val percent = (doc.progress * 100).toInt()
                        Text(if (doc.status == DocStatus.QUEUED) stringResource(R.string.library_queued) else stringResource(R.string.library_processing, percent), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LinearProgressIndicator(progress = { doc.progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp, end = 8.dp).height(3.dp))
                    }
                    else -> Text(
                        stringResource(R.string.library_subtitle, row.cardCount, row.knownCount, formatDate(doc.createdAt)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            FilledIconButton(
                onClick = onPlay,
                enabled = doc.status == DocStatus.READY,
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.size(44.dp).testTag("play-${doc.id}"),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.library_play))
            }
            Box {
                DropdownMenu(expanded = menuOpen, onDismissRequest = onDismissMenu) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_rename)) }, onClick = onRename)
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_reprocess)) }, onClick = onReprocess)
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = onDelete)
                }
            }
        }
    }
}
