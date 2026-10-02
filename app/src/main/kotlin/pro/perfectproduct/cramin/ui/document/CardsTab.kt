package pro.perfectproduct.cramin.ui.document

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pro.perfectproduct.cramin.R
import pro.perfectproduct.cramin.data.db.CardCounts
import pro.perfectproduct.cramin.data.db.DocStatus
import pro.perfectproduct.cramin.data.repo.DeckFilter
import pro.perfectproduct.cramin.ui.components.DirectionToggle
import pro.perfectproduct.cramin.ui.components.StatTile
import pro.perfectproduct.cramin.util.Lang

/** Вкладка «Карточки» (SPEC §9.5): сводка, фильтр колоды, «Учить», направление, перемешивание. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CardsTab(vm: DocumentViewModel, counts: CardCounts, onStudy: (String, Boolean) -> Unit) {
    val row by vm.document.collectAsState()
    val filter by vm.deckFilter.collectAsState()
    val shuffle by vm.shuffle.collectAsState()
    val canResume by vm.canResume.collectAsState()
    val direction by vm.direction.collectAsState()
    val doc = row?.document ?: return
    val src = Lang.fromCode(doc.sourceLang)
    val tgt = Lang.fromCode(doc.targetLang)
    val deckSize = when (filter) {
        DeckFilter.UNLEARNED -> counts.total - counts.known
        DeckFilter.ALL -> counts.total
        DeckFilter.STARRED -> counts.starred
    }
    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            StatTile(stringResource(R.string.cards_total), counts.total, Modifier.widthIn(min = 88.dp).weight(1f))
            StatTile(stringResource(R.string.cards_new), counts.newCount, Modifier.widthIn(min = 88.dp).weight(1f))
            StatTile(stringResource(R.string.cards_learning), counts.learning, Modifier.widthIn(min = 88.dp).weight(1f), color = MaterialTheme.colorScheme.tertiary)
            StatTile(stringResource(R.string.cards_known), counts.known, Modifier.widthIn(min = 88.dp).weight(1f), color = MaterialTheme.colorScheme.primary)
            StatTile(stringResource(R.string.cards_starred), counts.starred, Modifier.widthIn(min = 88.dp).weight(1f))
        }
        Spacer(Modifier.height(20.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = filter == DeckFilter.UNLEARNED, onClick = { vm.setDeckFilter(DeckFilter.UNLEARNED) }, label = { Text(stringResource(R.string.cards_filter_unlearned)) })
            FilterChip(selected = filter == DeckFilter.ALL, onClick = { vm.setDeckFilter(DeckFilter.ALL) }, label = { Text(stringResource(R.string.cards_filter_all)) })
            FilterChip(selected = filter == DeckFilter.STARRED, onClick = { vm.setDeckFilter(DeckFilter.STARRED) }, label = { Text(stringResource(R.string.cards_filter_starred)) })
        }
        Spacer(Modifier.height(20.dp))
        if (src != null && tgt != null) {
            DirectionToggle(src = src, tgt = tgt, direction = direction, onChange = vm::setDirection, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = shuffle, onCheckedChange = vm::setShuffle)
            Text(stringResource(R.string.cards_shuffle))
        }
        Spacer(Modifier.height(16.dp))
        val enabled = doc.status == DocStatus.READY && (deckSize > 0 || canResume)
        Button(onClick = { onStudy(vm.deckKey(), shuffle) }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("studyButton")) {
            Text(stringResource(R.string.cards_study) + if (deckSize > 0) " · $deckSize" else "", style = MaterialTheme.typography.titleMedium)
        }
        if (doc.status == DocStatus.READY && deckSize == 0) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(if (filter == DeckFilter.UNLEARNED && counts.total > 0) R.string.cards_all_learned else R.string.cards_empty_deck),
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}
