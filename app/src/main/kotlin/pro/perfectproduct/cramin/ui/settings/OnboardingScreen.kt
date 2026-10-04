package pro.perfectproduct.cramin.ui.settings

import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import pro.perfectproduct.cramin.ui.components.ActionDock
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pro.perfectproduct.cramin.R
import pro.perfectproduct.cramin.app.craminViewModel
import pro.perfectproduct.cramin.llm.ModelRole
import pro.perfectproduct.cramin.ui.components.SectionTitle

/** Онбординг из одного экрана (SPEC U1): что делает приложение, ключ, «Проверить ключ», модели. */
@Composable
fun OnboardingScreen(onDone: () -> Unit, vm: SettingsViewModel = craminViewModel { SettingsViewModel(it) }) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var advanced by rememberSaveable { mutableStateOf(false) }
    val effective by vm.effective.collectAsState()
    val hasKey by vm.hasKey.collectAsState()
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)) {
            Spacer(Modifier.height(32.dp))
            Text(stringResource(R.string.brand), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.onboarding_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.onboarding_access), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SectionTitle(stringResource(R.string.settings_openrouter))
            KeySection(vm, showRestoreNote = false)
            TextButton(onClick = { advanced = !advanced }, modifier = Modifier.testTag("onboardingAdvanced")) { Text(stringResource(R.string.onboarding_models_title) + if (advanced) " ▴" else " ▾") }
            if (advanced) {
                Text(stringResource(R.string.onboarding_models_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                for (role in ModelRole.entries) {
                    Text("${roleLabel(role)}: ${effective?.roles?.get(role)?.model ?: "—"}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        ActionDock {
            pro.perfectproduct.cramin.ui.components.PrimaryAction(stringResource(if (hasKey) R.string.onboarding_start else R.string.onboarding_skip), { focus.clearFocus(); keyboard?.hide(); vm.finishOnboarding(); onDone() }, Modifier.testTag("onboardingStart"))
        }
    }
}
