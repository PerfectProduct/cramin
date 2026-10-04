package pro.perfectproduct.cramin.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import pro.perfectproduct.cramin.BuildConfig
import pro.perfectproduct.cramin.R
import pro.perfectproduct.cramin.app.craminViewModel
import pro.perfectproduct.cramin.data.db.Direction
import pro.perfectproduct.cramin.llm.ConfigSource
import pro.perfectproduct.cramin.llm.ModelRole
import pro.perfectproduct.cramin.ui.components.LangSelector
import pro.perfectproduct.cramin.ui.components.SectionTitle
import pro.perfectproduct.cramin.ui.components.formatDate
import pro.perfectproduct.cramin.ui.components.formatUsd
import pro.perfectproduct.cramin.ui.components.langLabel
import pro.perfectproduct.cramin.ui.update.UpdateSection
import pro.perfectproduct.cramin.util.Lang

/** Настройки (SPEC §9.7): OpenRouter, модели, изучение, статистика, о приложении. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onPickModel: (String) -> Unit,
    onLicenses: () -> Unit,
    vm: SettingsViewModel = craminViewModel { SettingsViewModel(it) },
) {
    val settings by vm.settings.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            var section by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("study") }
            SettingsGroup(stringResource(R.string.settings_study), section == "study", { section = if (section == "study") "" else "study" }) {
                settings?.let { s -> StudySection(vm, s) }
            }
            SettingsGroup(stringResource(R.string.settings_processing), section == "processing", { section = if (section == "processing") "" else "processing" }) {
                SectionTitle(stringResource(R.string.settings_openrouter))
                KeySection(vm)
                SectionTitle(stringResource(R.string.settings_models))
                ModelsSection(vm, onPickModel)
                SectionTitle(stringResource(R.string.settings_stats))
                StatsSection(vm)
            }
            SettingsGroup(stringResource(R.string.settings_application), section == "app", { section = if (section == "app") "" else "app" }) {
                AboutSection(onLicenses)
            }
        }
    }
}

@Composable
fun KeySection(vm: SettingsViewModel, showRestoreNote: Boolean = true) {
    val hasKey by vm.hasKey.collectAsState()
    val hint by vm.keyHint.collectAsState()
    val keyUi by vm.keyUi.collectAsState()
    var input by remember { mutableStateOf("") }
    Column {
        Text(if (hasKey) stringResource(R.string.settings_key_saved, hint ?: "····") else stringResource(R.string.settings_key_none), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("keyStatus"))
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = input, onValueChange = { input = it }, singleLine = true,
            label = { Text(stringResource(R.string.settings_key)) },
            placeholder = { Text(stringResource(R.string.settings_key_hint)) },
            // Ключ никогда не показывается целиком (SPEC §11): маскированный ввод.
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().testTag("keyField"),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { if (input.isNotBlank()) { vm.saveAndCheckKey(input); input = "" } else vm.checkKey() }, modifier = Modifier.testTag("keyCheck")) {
                Text(stringResource(if (input.isNotBlank()) R.string.action_save else R.string.settings_key_check))
            }
            if (hasKey) TextButton(onClick = vm::removeKey) { Text(stringResource(R.string.settings_key_remove)) }
        }
        val text = when (val k = keyUi) {
            KeyUi.Idle -> null
            KeyUi.Checking -> stringResource(R.string.update_checking)
            is KeyUi.Valid -> if (k.remainingUsd != null) stringResource(R.string.settings_key_valid_credit, formatUsd(k.remainingUsd)) else stringResource(R.string.settings_key_valid)
            KeyUi.Invalid -> stringResource(R.string.settings_key_invalid)
            is KeyUi.Error -> stringResource(R.string.settings_key_error, k.detail)
        }
        if (text != null) {
            Text(text, color = if (keyUi is KeyUi.Valid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp).testTag("keyResult"))
        }
        if (showRestoreNote) {
            Text(stringResource(R.string.settings_key_restore_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun ModelsSection(vm: SettingsViewModel, onPickModel: (String) -> Unit) {
    val effective by vm.effective.collectAsState()
    val updatedAt by vm.remoteUpdatedAt.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    Column {
        for (role in ModelRole.entries) {
            val r = effective?.roles?.get(role)
            Row(modifier = Modifier.fillMaxWidth().clickable(enabled = role != ModelRole.TOPIC) { onPickModel(role.key) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(roleLabel(role), style = MaterialTheme.typography.titleSmall)
                    Text(r?.model ?: "—", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        r?.let { sourceLabel(it.source) } ?: "",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (role != ModelRole.TOPIC) Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        effective?.warnings?.forEach { w -> Text("⚠ $w", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::refreshModels, enabled = !refreshing) { Text(stringResource(R.string.settings_models_refresh)) }
            Text(
                if (updatedAt > 0) stringResource(R.string.settings_models_updated, formatDate(updatedAt)) else stringResource(R.string.settings_models_never),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun roleLabel(role: ModelRole): String = stringResource(
    when (role) {
        ModelRole.BRIEF -> R.string.role_brief
        ModelRole.TRANSLATE -> R.string.role_translate
        ModelRole.EXTRACT -> R.string.role_extract
        ModelRole.CONSOLIDATE -> R.string.role_consolidate
        ModelRole.STT -> R.string.role_stt
        ModelRole.TOPIC -> R.string.role_topic
    },
)

@Composable
fun sourceLabel(source: ConfigSource): String = stringResource(
    when (source) {
        ConfigSource.OVERRIDE -> R.string.settings_model_source_override
        ConfigSource.REMOTE -> R.string.settings_model_source_remote
        ConfigSource.EMBEDDED -> R.string.settings_model_source_embedded
    },
)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun StudySection(vm: SettingsViewModel, s: pro.perfectproduct.cramin.data.prefs.Settings) {
    val ttsLangs by vm.ttsLangs.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.settings_default_target), style = MaterialTheme.typography.titleSmall)
        LangSelector(selected = Lang.fromCode(s.defaultTargetLang), onSelect = vm::setDefaultTargetLang)
        Text(stringResource(R.string.settings_default_direction), style = MaterialTheme.typography.titleSmall)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = s.defaultDirection == Direction.SRC_FRONT, onClick = { vm.setDefaultDirection(Direction.SRC_FRONT) }, label = { Text(stringResource(R.string.settings_direction_src)) })
            FilterChip(selected = s.defaultDirection == Direction.TGT_FRONT, onClick = { vm.setDefaultDirection(Direction.TGT_FRONT) }, label = { Text(stringResource(R.string.settings_direction_tgt)) })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_autospeak), Modifier.weight(1f))
            Switch(checked = s.autoSpeak, onCheckedChange = vm::setAutoSpeak)
        }
        Text(stringResource(R.string.settings_tts_rate) + ": ${"%.1f".format(s.ttsRate)}×", style = MaterialTheme.typography.titleSmall)
        Slider(value = s.ttsRate, onValueChange = { vm.setTtsRate(it) }, valueRange = 0.5f..2f, steps = 14)
        for (lang in Lang.entries) if (lang !in ttsLangs) {
            Text(stringResource(R.string.settings_tts_missing, langLabel(lang)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(stringResource(R.string.settings_autoplay), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.study_interval_front) + ": ${s.autoplayFrontMs / 1000f}", style = MaterialTheme.typography.labelLarge)
        Slider(value = s.autoplayFrontMs / 1000f, onValueChange = { vm.setIntervals((it * 1000).toInt(), s.autoplayBackMs) }, valueRange = 1f..10f, steps = 17)
        Text(stringResource(R.string.study_interval_back) + ": ${s.autoplayBackMs / 1000f}", style = MaterialTheme.typography.labelLarge)
        Slider(value = s.autoplayBackMs / 1000f, onValueChange = { vm.setIntervals(s.autoplayFrontMs, (it * 1000).toInt()) }, valueRange = 1f..10f, steps = 17)
    }
}

@Composable
private fun StatsSection(vm: SettingsViewModel) {
    val usage by vm.usage.collectAsState()
    usage?.let { u ->
        Text(stringResource(R.string.settings_stats_body, u.documents, "%,d".format(u.promptTokens), "%,d".format(u.completionTokens), formatUsd(u.costUsd)), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AboutSection(onLicenses: () -> Unit) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("version"))
        UpdateSection()
        TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL))) }) { Text(stringResource(R.string.settings_repo)) }
        TextButton(onClick = onLicenses) { Text(stringResource(R.string.settings_licenses)) }
    }
}

const val REPO_URL = "https://github.com/PerfectProduct/cramin"

@Composable
private fun SettingsGroup(title: String, expanded: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(title + if (expanded) " ▴" else " ▾", style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth())
    }
    if (expanded) content()
}
