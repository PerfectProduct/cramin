package pro.perfectproduct.cramin.ui.document

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import pro.perfectproduct.cramin.R
import pro.perfectproduct.cramin.data.db.TopicCategory

@Composable
fun ChoiceRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, explanation: String? = null) {
    Surface(shape = RoundedCornerShape(10.dp), color = if (checked && explanation != null) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surface,
        border = if (explanation != null) BorderStroke(1.dp, if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.65f) else MaterialTheme.colorScheme.outlineVariant) else null,
        modifier = Modifier.fillMaxWidth().padding(bottom = if (explanation != null) 8.dp else 0.dp)) {
        Row(modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Checkbox, onValueChange = onChange).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked, onCheckedChange = null, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                explanation?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
fun TopicFilters(mask: Int, toggle: (Int) -> Unit) {
    var hint by rememberSaveable { mutableStateOf(false) }
    Text(stringResource(R.string.topic_question), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
    TopicCategory.entries.forEach { category ->
        ChoiceRow(stringResource(when (category) {
            TopicCategory.CORE -> R.string.topic_core
            TopicCategory.RELATED -> R.string.topic_related
            TopicCategory.GENERAL -> R.string.topic_general
        }), mask and category.bit != 0, { toggle(category.bit) }, Modifier.testTag("category_${category.name}"), stringResource(when (category) {
            TopicCategory.CORE -> R.string.topic_core_help
            TopicCategory.RELATED -> R.string.topic_related_help
            TopicCategory.GENERAL -> R.string.topic_general_help
        }))
    }
    if (mask == 0) Text(stringResource(R.string.topic_choose))
    TextButton(onClick = { hint = !hint }) { Text(stringResource(R.string.topic_start_hint)) }
    if (hint) Text(stringResource(R.string.topic_start_help), style = MaterialTheme.typography.bodySmall)
}
