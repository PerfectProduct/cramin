package pro.perfectproduct.cramin.ui.document

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pro.perfectproduct.cramin.R
import pro.perfectproduct.cramin.data.db.CardCounts
import pro.perfectproduct.cramin.data.db.DocStatus
import pro.perfectproduct.cramin.data.db.Direction
import pro.perfectproduct.cramin.data.repo.DeckFilter
import pro.perfectproduct.cramin.ui.components.DirectionToggle
import pro.perfectproduct.cramin.ui.components.langLabel
import pro.perfectproduct.cramin.util.Lang

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CardsTab(vm: DocumentViewModel, counts: CardCounts, onStudy: (String, Boolean) -> Unit, header: @Composable () -> Unit = {}) {
    val row by vm.document.collectAsState()
    val filter by vm.deckFilter.collectAsState()
    val shuffle by vm.shuffle.collectAsState()
    val canResume by vm.canResume.collectAsState()
    val direction by vm.direction.collectAsState()
    val selection by vm.selection.collectAsState()
    val mask by vm.categoryMask.collectAsState()
    val categoryWorkRunning by vm.categoryWorkRunning.collectAsState()
    var totals by rememberSaveable { mutableStateOf(false) }
    var options by rememberSaveable { mutableStateOf(false) }
    var enrichment by rememberSaveable { mutableStateOf(false) }
    val doc = row?.document ?: return
    val src = Lang.fromCode(doc.sourceLang)
    val tgt = Lang.fromCode(doc.targetLang)
    val deckSize = selection.first
    val unlearned = filter == DeckFilter.UNLEARNED || filter == DeckFilter.UNLEARNED_STARRED
    val starred = filter == DeckFilter.STARRED || filter == DeckFilter.UNLEARNED_STARRED
    fun setFilter(onlyUnlearned: Boolean, onlyStarred: Boolean) = vm.setDeckFilter(when {
        onlyUnlearned && onlyStarred -> DeckFilter.UNLEARNED_STARRED
        onlyUnlearned -> DeckFilter.UNLEARNED
        onlyStarred -> DeckFilter.STARRED
        else -> DeckFilter.ALL
    })
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        header()
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            TextButton(onClick = { totals = !totals }, modifier = Modifier.testTag("documentProgress")) {
                Text(stringResource(R.string.doc_progress_summary, counts.known, counts.total) + if (totals) " ▴" else " ▾")
            }
            if (totals) Text(stringResource(R.string.doc_progress_details, counts.newCount, counts.learning, counts.known, counts.starred))
            TopicFilters(mask, vm::toggleCategory)
            if (selection.second > 0) {
                Text(stringResource(R.string.topic_incomplete, selection.second), style = MaterialTheme.typography.bodySmall)
                if (doc.topicError != null) Text(stringResource(R.string.topic_error))
                Text(stringResource(R.string.topic_api_notice), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { enrichment = true }, enabled = doc.status == DocStatus.READY && !categoryWorkRunning) {
                    Text(stringResource(if (categoryWorkRunning) R.string.topic_running else if (selection.second == counts.total) R.string.topic_determine else R.string.topic_continue))
                }
            }
            Text(stringResource(R.string.cards_selection), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = unlearned, onClick = { setFilter(true, starred) }, label = { Text(stringResource(R.string.cards_filter_unlearned)) })
                FilterChip(selected = !unlearned, onClick = { setFilter(false, starred) }, label = { Text(stringResource(R.string.cards_filter_all)) })
            }
            ChoiceRow(stringResource(R.string.cards_only_starred), starred, { setFilter(unlearned, it) }, Modifier.testTag("onlyStarred"))
            if (src != null && tgt != null) {
                TextButton(onClick = { options = true }, modifier = Modifier.fillMaxWidth().testTag("sessionOptions")) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.session_options), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.session_summary, langLabel(if (direction == Direction.SRC_FRONT) src else tgt), stringResource(if (shuffle) R.string.session_shuffled else R.string.session_ordered)), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = { onStudy(vm.deckKey(), shuffle) }, enabled = doc.status == DocStatus.READY && mask != 0 && deckSize > 0,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("studyButton")) {
                Text(stringResource(R.string.cards_study) + " · $deckSize", style = MaterialTheme.typography.titleMedium)
            }
            // A saved round can contain known cards even when today's selection is empty.
            if (doc.status == DocStatus.READY && mask != 0 && deckSize == 0 && canResume) {
                TextButton(onClick = { onStudy(vm.deckKey(), shuffle) }, modifier = Modifier.testTag("resumeSaved")) { Text(stringResource(R.string.session_resume_saved)) }
            }
            if (doc.status == DocStatus.READY && deckSize == 0 && mask != 0) Text(stringResource(R.string.cards_empty_deck), style = MaterialTheme.typography.bodySmall)
            if (doc.studyNotice) Text(stringResource(R.string.study_migration_notice), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (options && src != null && tgt != null) ModalBottomSheet(onDismissRequest = { options = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.session_options), style = MaterialTheme.typography.titleLarge)
            DirectionToggle(src, tgt, direction, vm::setDirection)
            ChoiceRow(stringResource(R.string.cards_shuffle), shuffle, vm::setShuffle)
            TextButton(onClick = { options = false }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_ok)) }
        }
    }
    if (enrichment) AlertDialog(onDismissRequest = { enrichment = false }, title = { Text(stringResource(R.string.topic_determine)) },
        text = { Text(stringResource(R.string.topic_enrichment_confirm), Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { enrichment = false; vm.determineCategories() }) { Text(stringResource(R.string.consolidation_api_start)) } },
        dismissButton = { TextButton(onClick = { enrichment = false }) { Text(stringResource(R.string.action_cancel)) } })
}
