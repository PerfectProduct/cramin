package pro.perfectproduct.cramin.ui.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pro.perfectproduct.cramin.R
import pro.perfectproduct.cramin.app.craminViewModel
import pro.perfectproduct.cramin.app.theme.study
import pro.perfectproduct.cramin.study.SessionEvent
import pro.perfectproduct.cramin.ui.components.CounterPill
import pro.perfectproduct.cramin.ui.components.DirectionToggle
import pro.perfectproduct.cramin.ui.components.EmptyState
import pro.perfectproduct.cramin.ui.components.RoundIconButton

/** Экран сессии (SPEC §9.6). Логика — в StudySessionMachine, здесь только рендер и жесты. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyScreen(
    deckKey: String,
    shuffle: Boolean,
    onClose: () -> Unit,
    vm: StudyViewModel = craminViewModel(key = "study-$deckKey") { StudyViewModel(it, deckKey, shuffle) },
) {
    val state by vm.state.collectAsState()
    val session = state.session
    var settingsOpen by remember { mutableStateOf(false) }

    if (state.migrationReset) {
        AlertDialog(onDismissRequest = vm::acknowledgeMigration,
            text = { Text(stringResource(R.string.study_migration_notice)) },
            confirmButton = { TextButton(onClick = vm::acknowledgeMigration) { Text(stringResource(R.string.action_ok)) } })
    }

    Scaffold(
        modifier = Modifier.pointerInput(vm) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    if (event.changes.any { it.pressed && !it.previousPressed }) vm.interactionStart()
                }
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    if (session != null) {
                        Text(
                            stringResource(R.string.study_counter, minOf(session.position + 1, session.total), session.total),
                            style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("counter"),
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onClose, modifier = Modifier.testTag("studyClose")) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close)) } },
                actions = { IconButton(onClick = { settingsOpen = true }) { Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.study_settings)) } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.empty) {
                EmptyState("🗂", stringResource(R.string.study_empty))
                return@Column
            }
            if (session == null) return@Column
            LinearProgressIndicator(progress = { if (session.total == 0) 0f else session.position.toFloat() / session.total }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                CounterPill(session.learningThisRound, MaterialTheme.study.learning, Modifier.testTag("learningCount"))
                CounterPill(session.knownThisRound, MaterialTheme.study.known, Modifier.testTag("knownCount"))
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
                val card = state.currentCard
                if (card != null) {
                    androidx.compose.runtime.key(card.id, session.round) {
                        FlashCard(
                            card = card,
                            direction = state.direction,
                            flipped = session.isFlipped,
                            ttsLangs = state.ttsLangs,
                            onFlip = { vm.dispatch(SessionEvent.Flip) },
                            onSwipeRight = { vm.dispatch(SessionEvent.SwipeRight) },
                            onSwipeLeft = { vm.dispatch(SessionEvent.SwipeLeft) },
                            onSpeak = vm::speak,
                            onStar = { vm.toggleStar(card) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            Text(stringResource(R.string.study_swipe_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                RoundIconButton("↶", stringResource(R.string.study_undo), onClick = { vm.dispatch(SessionEvent.Undo) }, enabled = session.canUndo, modifier = Modifier.testTag("undo"))
                RoundIconButton(
                    if (session.autoplay) "⏸" else "▶",
                    stringResource(if (session.autoplay) R.string.study_pause else R.string.study_autoplay),
                    onClick = vm::autoplayPointerClick,
                    onAccessibilityClick = { vm.dispatch(SessionEvent.ToggleAutoplay) },
                    enabled = !session.finished,
                    modifier = Modifier.testTag("autoplay"),
                )
            }
        }
    }

    if (session != null && session.finished) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.study_round_title, session.knownThisRound, session.roundSize), modifier = Modifier.testTag("roundSummary")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.dispatch(SessionEvent.Undo) }, enabled = session.canUndo, modifier = Modifier.fillMaxWidth().testTag("roundUndo")) {
                        Text(stringResource(R.string.study_undo))
                    }
                    if (session.learningIdsThisRound.isNotEmpty()) {
                        TextButton(onClick = { vm.dispatch(SessionEvent.RepeatLearning) }, modifier = Modifier.fillMaxWidth().testTag("repeatLearning")) {
                            Text(stringResource(R.string.study_repeat_learning, session.learningIdsThisRound.size))
                        }
                    }
                    TextButton(onClick = { vm.dispatch(SessionEvent.RestartAll) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.study_restart)) }
                }
            },
            confirmButton = { TextButton(onClick = onClose, modifier = Modifier.testTag("roundDone")) { Text(stringResource(R.string.study_done)) } },
        )
    }

    state.resumeCandidate?.let { saved ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.study_resume_title)) },
            confirmButton = {
                TextButton(onClick = vm::resume, modifier = Modifier.testTag("resume")) { Text(stringResource(R.string.study_resume_continue, saved.position, saved.total)) }
            },
            dismissButton = { TextButton(onClick = vm::restartInsteadOfResume) { Text(stringResource(R.string.study_restart)) } },
        )
    }

    if (settingsOpen) {
        ModalBottomSheet(onDismissRequest = { settingsOpen = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val src = state.srcLang
                val tgt = state.tgtLang
                if (src != null && tgt != null) {
                    DirectionToggle(src, tgt, state.direction, onChange = vm::setDirection, modifier = Modifier.align(Alignment.CenterHorizontally))
                }
                TextButton(onClick = { vm.reshuffle(); settingsOpen = false }) { Text(stringResource(R.string.cards_shuffle)) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.study_autospeak))
                    Switch(checked = state.autoSpeak, onCheckedChange = vm::setAutoSpeak)
                }
                Text(stringResource(R.string.study_interval_front) + ": ${state.frontMs / 1000f}", style = MaterialTheme.typography.labelLarge)
                Slider(value = state.frontMs / 1000f, onValueChange = { vm.setIntervals((it * 1000).toInt(), state.backMs) }, valueRange = 1f..10f, steps = 17)
                Text(stringResource(R.string.study_interval_back) + ": ${state.backMs / 1000f}", style = MaterialTheme.typography.labelLarge)
                Slider(value = state.backMs / 1000f, onValueChange = { vm.setIntervals(state.frontMs, (it * 1000).toInt()) }, valueRange = 1f..10f, steps = 17)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
