package pro.perfectproduct.cramin.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import org.json.JSONArray
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
    val assets = LocalContext.current.assets
    val notices = remember {
        val index = JSONArray(assets.open("legal/index.json").bufferedReader().use { it.readText() })
        (0 until index.length()).map { i ->
            index.getJSONObject(i).let { it.getString("title") to it.getString("file") }
        }
    }
    val inventory = remember {
        val rows = JSONArray(assets.open("legal/inventory.json").bufferedReader().use { it.readText() })
        (0 until rows.length()).joinToString("\n") { i ->
            val row = rows.getJSONObject(i)
            "${row.getString("group")}:${row.getString("module")}:${row.getString("version")}"
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_licenses)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            item {
                Text(stringResource(R.string.licenses_explanation), modifier = Modifier.padding(vertical = 16.dp))
            }
            item { NoticeSection(stringResource(R.string.licenses_inventory), inventory) }
            items(notices, key = { it.second }) { (title, file) ->
                val body = remember(file) { assets.open("legal/$file").bufferedReader().use { it.readText() } }
                NoticeSection(title, body)
            }
        }
    }
}


@Composable
private fun NoticeSection(title: String, body: String) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.padding(vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.clickable { expanded = !expanded }.padding(vertical = 8.dp))
        if (expanded) Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}
