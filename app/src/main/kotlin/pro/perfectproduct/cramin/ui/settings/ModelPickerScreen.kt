package pro.perfectproduct.cramin.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pro.perfectproduct.cramin.R
import pro.perfectproduct.cramin.app.craminViewModel
import pro.perfectproduct.cramin.llm.CatalogModel
import pro.perfectproduct.cramin.llm.ModelRole
import pro.perfectproduct.cramin.llm.RoleOverride

/**
 * Выбор модели роли (SPEC §9.7): список из каталога (для текстовых ролей — только со structured outputs),
 * сортировка по цене, поиск, ручной ввод id, «Сбросить к конфигу».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerScreen(roleKey: String, onBack: () -> Unit, vm: SettingsViewModel = craminViewModel { SettingsViewModel(it) }) {
    val role = ModelRole.fromKey(roleKey) ?: return
    val effective by vm.effective.collectAsState()
    var query by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf("") }
    var models by remember { mutableStateOf<List<CatalogModel>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(role) {
        models = if (role.isText) vm.catalogModels().sortedBy { (it.promptPricePerMillion ?: 0.0) + (it.completionPricePerMillion ?: 0.0) } else emptyList()
        loaded = true
    }
    val current = effective?.roles?.get(role)
    val filtered = models.filter { query.isBlank() || it.id.contains(query, ignoreCase = true) || it.name.contains(query, ignoreCase = true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(roleLabel(role)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            Text(current?.model ?: "—", style = MaterialTheme.typography.titleMedium)
            Text(current?.let { sourceLabel(it.source) } ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { vm.setOverride(role, null) }) { Text(stringResource(R.string.settings_models_reset)) }
            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(value = manual, onValueChange = { manual = it }, singleLine = true, label = { Text(stringResource(R.string.settings_model_manual)) }, modifier = Modifier.weight(1f))
                Button(onClick = { if (manual.isNotBlank()) { vm.setOverride(role, RoleOverride(model = manual.trim())); manual = "" } }, modifier = Modifier.padding(start = 8.dp, top = 8.dp)) { Text(stringResource(R.string.action_save)) }
            }
            if (role.isText) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, label = { Text(stringResource(R.string.settings_model_search)) }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                if (loaded && models.isEmpty()) Text(stringResource(R.string.err_network), color = MaterialTheme.colorScheme.error)
                LazyColumn {
                    items(filtered, key = { it.id }) { m ->
                        Column(modifier = Modifier.fillMaxWidth().clickable { vm.setOverride(role, RoleOverride(model = m.id)); onBack() }.padding(vertical = 8.dp)) {
                            Text(m.id, style = MaterialTheme.typography.bodyMedium, color = if (m.id == current?.model) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            Text(
                                stringResource(R.string.settings_model_price, "%.2f".format(m.promptPricePerMillion ?: 0.0), "%.2f".format(m.completionPricePerMillion ?: 0.0)) + "  · ctx ${m.contextLength ?: "?"}",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
