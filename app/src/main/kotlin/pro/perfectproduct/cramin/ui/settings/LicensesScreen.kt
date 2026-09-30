package pro.perfectproduct.cramin.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pro.perfectproduct.cramin.R

/** Лицензии открытых библиотек (SPEC §9.7). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_licenses)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp)) {
            for ((name, license) in LIBRARIES) {
                Text(name, style = MaterialTheme.typography.titleSmall)
                Text(license, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
            }
        }
    }
}

private val LIBRARIES = listOf(
    "Kotlin, kotlinx.coroutines, kotlinx.serialization" to "Apache License 2.0 — JetBrains",
    "AndroidX: Compose, Room, WorkManager, DataStore, Navigation, Lifecycle" to "Apache License 2.0 — The Android Open Source Project",
    "OkHttp, Okio" to "Apache License 2.0 — Square, Inc.",
    "Readability4J" to "Apache License 2.0 — dankito",
    "jsoup" to "MIT License — Jonathan Hedley",
    "NewPipeExtractor" to "GNU General Public License v3.0 — Team NewPipe (используется без изменений; исходники: https://github.com/TeamNewPipe/NewPipeExtractor)",
    "nanojson" to "Apache License 2.0",
    "Rhino" to "Mozilla Public License 2.0 — Mozilla",
    "PdfBox-Android" to "Apache License 2.0 — Tom Roush; Apache PDFBox — Apache Software Foundation",
    "protobuf-javalite" to "BSD 3-Clause — Google",
    "desugar_jdk_libs" to "GNU GPL v2 with Classpath Exception — Google",
)
