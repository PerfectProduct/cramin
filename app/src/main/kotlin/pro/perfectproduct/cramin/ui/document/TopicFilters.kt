package pro.perfectproduct.cramin.ui.document

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pro.perfectproduct.cramin.R
import pro.perfectproduct.cramin.data.db.TopicCategory

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TopicFilters(mask: Int, toggle: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TopicCategory.entries.forEach { category ->
            FilterChip(selected = mask and category.bit != 0, onClick = { toggle(category.bit) },
                modifier = Modifier.testTag("category_${category.name}"), label = {
                    Text(stringResource(when (category) {
                        TopicCategory.CORE -> R.string.topic_core
                        TopicCategory.RELATED -> R.string.topic_related
                        TopicCategory.GENERAL -> R.string.topic_general
                    }))
                })
        }
    }
    if (mask == 0) Text(stringResource(R.string.topic_choose))
}
