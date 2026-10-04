package pro.perfectproduct.cramin.ui.study

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pro.perfectproduct.cramin.R
import pro.perfectproduct.cramin.app.AppContainer
import pro.perfectproduct.cramin.app.craminViewModel
import pro.perfectproduct.cramin.data.db.Direction
import pro.perfectproduct.cramin.data.db.LangPair
import pro.perfectproduct.cramin.data.repo.DeckKey
import pro.perfectproduct.cramin.ui.components.*
import pro.perfectproduct.cramin.ui.components.EmptyState
import pro.perfectproduct.cramin.util.Lang

class AllDeckViewModel(private val container: AppContainer) : ViewModel() {
    val pairs: StateFlow<List<LangPair>> = container.cardRepository.observeLangPairs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val selected = kotlinx.coroutines.flow.MutableStateFlow<LangPair?>(null)

    val categoryMask = container.settingsStore.allCategoryMask.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 7)
    fun toggleCategory(bit: Int) = viewModelScope.launch { container.settingsStore.toggleAllCategory(bit) }
    val unknown = kotlinx.coroutines.flow.MutableStateFlow(0)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val current: StateFlow<Pair<LangPair, Int>?> = combine(pairs, selected) { all, sel -> sel?.takeIf { it in all } ?: all.firstOrNull() }
        .flatMapLatest { pair ->
            if (pair == null) flowOf(null) else combine(
                container.cardRepository.observePairCards(Lang.requireCode(pair.lang), Lang.requireCode(pair.targetLang)), categoryMask
            ) { cards, mask ->
                unknown.value = cards.count { it.category == null }
                pair to cards.filter { pro.perfectproduct.cramin.data.repo.CategoryFilter.accepts(it.category, mask) }
                    .groupBy { it.lemmaKey to it.meaningKey }.values.count { group -> group.any { it.status != pro.perfectproduct.cramin.data.db.CardStatus.KNOWN } }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val canResume: StateFlow<Boolean> = combine(current, categoryMask) { c, m -> c to m }.flatMapLatest { (cur, mask) ->
        if (cur == null) flowOf(false) else container.studyRepository.observeResumable(
            DeckKey.All(Lang.requireCode(cur.first.lang), Lang.requireCode(cur.first.targetLang), mask).key)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val direction: StateFlow<Direction> = combine(current, container.settingsStore.settings) { cur, s -> cur?.first to s.defaultDirection }
        .flatMapLatest { (pair, def) ->
            if (pair == null) flowOf(def) else container.settingsStore.allDeckDirection(pair.lang, pair.targetLang).map { it ?: def }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Direction.SRC_FRONT)

    fun select(pair: LangPair) { selected.value = pair }
    fun setDirection(d: Direction) = viewModelScope.launch {
        val pair = current.value?.first ?: return@launch
        container.settingsStore.setAllDeckDirection(pair.lang, pair.targetLang, d)
    }
}

/** Общая колода «Все невыученные» (SPEC §10.6): чипы пар языков, направление, «Учить». */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AllDeckScreen(onBack: () -> Unit, onStudy: (String, Boolean) -> Unit, vm: AllDeckViewModel = craminViewModel { AllDeckViewModel(it) }) {
    val pairs by vm.pairs.collectAsState()
    val current by vm.current.collectAsState()
    val direction by vm.direction.collectAsState()
    val canResume by vm.canResume.collectAsState()
    val mask by vm.categoryMask.collectAsState()
    val unknown by vm.unknown.collectAsState()
    var shuffle by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.alldeck_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
            )
        },
        bottomBar = { current?.let { cur ->
            ActionDock(Modifier.navigationBarsPadding()) {
                PrimaryAction(stringResource(R.string.cards_study) + " · ${cur.second}", { onStudy(DeckKey.All(Lang.requireCode(cur.first.lang), Lang.requireCode(cur.first.targetLang), mask).key, shuffle) }, Modifier.testTag("allDeckStudy"), mask != 0 && (cur.second > 0 || canResume))
            }
        } },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            if (pairs.isEmpty()) {
                EmptyState("🗂", stringResource(R.string.alldeck_empty))
                return@Column
            }
            SingleChoice(pairs, current?.first, { "${langLabel(Lang.requireCode(it.lang))} → ${langLabel(Lang.requireCode(it.targetLang))}" }, vm::select)
            Spacer(Modifier.height(20.dp))
            val cur = current
            if (cur != null) {
                pro.perfectproduct.cramin.ui.document.TopicFilters(mask, vm::toggleCategory)
                if (unknown > 0) Text(stringResource(R.string.topic_incomplete, unknown))
                Spacer(Modifier.height(16.dp))
                NavigationRow(stringResource(R.string.session_options), stringResource(R.string.session_summary, langLabel(Lang.requireCode(if (direction == Direction.SRC_FRONT) cur.first.lang else cur.first.targetLang)), stringResource(if (shuffle) R.string.session_shuffled else R.string.session_ordered)), Modifier.testTag("allDeckOptions")) { options = true }
                if (options) androidx.compose.material3.ModalBottomSheet(onDismissRequest = { options = false }) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                        DirectionToggle(Lang.requireCode(cur.first.lang), Lang.requireCode(cur.first.targetLang), direction, vm::setDirection)
                        pro.perfectproduct.cramin.ui.document.ChoiceRow(stringResource(R.string.cards_shuffle), shuffle, { shuffle = it })
                        PrimaryAction(stringResource(R.string.action_ok), { options = false })
                    }
                }
                if (cur.second == 0) {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.alldeck_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
