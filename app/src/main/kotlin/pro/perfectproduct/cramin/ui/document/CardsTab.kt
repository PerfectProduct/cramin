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
import pro.perfectproduct.cramin.ui.components.*
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
    Column(Modifier.fillMaxSize()) {
      Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
        header()
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            TextButton(onClick = { totals = !totals }, modifier = Modifier.testTag("documentProgress")) {
                Text(stringResource(R.string.doc_progress_summary, counts.known, counts.total) + if (totals) " ▴" else " ▾")
            }
            if (totals) Text(stringResource(R.string.doc_progress_details, counts.newCount, counts.learning, counts.known, counts.starred))
            val noCategories = counts.total > 0 && selection.second == counts.total
            if (noCategories) {
                Text(stringResource(R.string.topic_not_defined), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp).testTag("noCategories"))
                Text(stringResource(R.string.topic_not_defined_help), style = MaterialTheme.typography.bodyMedium)
            } else TopicFilters(mask, vm::toggleCategory)
            if (selection.second > 0) {
                if (!noCategories) Text(stringResource(R.string.topic_incomplete, selection.second), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("unclassifiedCount"))
                if (mask != pro.perfectproduct.cramin.data.repo.CategoryFilter.ALL) {
                    if (noCategories) Text(stringResource(R.string.topic_saved_subset), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = vm::openFullCategorySet, modifier = Modifier.testTag("openFullCategorySet")) { Text(stringResource(R.string.topic_open_full)) }
                }
                if (doc.topicError != null) Text(stringResource(R.string.topic_error))
                Text(stringResource(R.string.topic_paid_short), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { enrichment = true }, enabled = doc.status == DocStatus.READY && !categoryWorkRunning, modifier = Modifier.testTag("enrichCategories")) {
                    Text(stringResource(if (categoryWorkRunning) R.string.topic_running else if (selection.second == counts.total) R.string.topic_determine else R.string.topic_continue))
                }
            }
            Text(stringResource(R.string.cards_selection), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp))
            SingleChoice(listOf(true, false), unlearned, { stringResource(if (it) R.string.cards_filter_unlearned else R.string.cards_filter_all) }, { setFilter(it, starred) }, Modifier.padding(top = 8.dp))
            ChoiceRow(stringResource(R.string.cards_only_starred), starred, { setFilter(unlearned, it) }, Modifier.testTag("onlyStarred"))
            if (src != null && tgt != null) {
                NavigationRow(stringResource(R.string.session_options),
                    stringResource(R.string.session_summary, langLabel(if (direction == Direction.SRC_FRONT) src else tgt), stringResource(if (shuffle) R.string.session_shuffled else R.string.session_ordered)),
                    Modifier.testTag("sessionOptions"), onClick = { options = true })
            }
            if (doc.studyNotice) StatusText(stringResource(R.string.study_migration_notice))
        }
      }
      ActionDock {

            PrimaryAction(stringResource(R.string.cards_study) + " · $deckSize", { onStudy(vm.deckKey(), shuffle) }, Modifier.testTag("studyButton"), enabled = doc.status == DocStatus.READY && mask != 0 && deckSize > 0)
            // A saved round can contain known cards even when today's selection is empty.
            if (doc.status == DocStatus.READY && mask != 0 && deckSize == 0 && canResume) {
                TextButton(onClick = { onStudy(vm.deckKey(), shuffle) }, modifier = Modifier.testTag("resumeSaved")) { Text(stringResource(R.string.session_resume_saved)) }
            }
            if (doc.status == DocStatus.READY && deckSize == 0 && mask != 0) Text(stringResource(if (counts.total > 0) R.string.cards_empty_filter else R.string.cards_empty_deck), style = MaterialTheme.typography.bodySmall)
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
