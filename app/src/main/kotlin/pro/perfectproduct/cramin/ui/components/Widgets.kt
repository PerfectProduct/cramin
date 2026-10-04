package pro.perfectproduct.cramin.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pro.perfectproduct.cramin.data.db.Direction
import pro.perfectproduct.cramin.util.Lang

/** Плитка сводки «Всего / Новые / Учу / Знаю / Избранные» (SPEC §9.5). */
@Composable
fun StatTile(label: String, value: Int, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(14.dp)).padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value.toString(), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Тумблер направления в стиле переключателя темы с подписями языков по краям: `EN ◐ RU` (SPEC §9.5). */
@Composable
fun DirectionToggle(src: Lang, tgt: Lang, direction: Direction, onChange: (Direction) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.testTag("directionToggle")) {
        Text(androidx.compose.ui.res.stringResource(pro.perfectproduct.cramin.R.string.session_first_language), style = MaterialTheme.typography.titleSmall)
        listOf(src to Direction.SRC_FRONT, tgt to Direction.TGT_FRONT).forEach { (lang, value) ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = direction == value, role = androidx.compose.ui.semantics.Role.RadioButton, onClick = { onChange(value) }), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.RadioButton(selected = direction == value, onClick = null)
                Text(langLabel(lang), Modifier.padding(start = 8.dp))
            }
        }
    }
}

/** Круглая пилюля-счётчик «ещё учу» / «знаю» в сессии (SPEC §9.6). */
@Composable
fun CounterPill(value: Int, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.widthIn(min = 56.dp).heightIn(min = 36.dp).background(color.copy(alpha = 0.18f), RoundedCornerShape(18.dp)).padding(horizontal = 8.dp, vertical = 4.dp).semantics(mergeDescendants = true) {},
        contentAlignment = Alignment.Center,
    ) {
        Text(value.toString(), color = color, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
    }
}

@Composable
fun RoundIconButton(emoji: String, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, onAccessibilityClick: () -> Unit = onClick) {
    Box(
        modifier = modifier
            .size(56.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
            .semantics { this.contentDescription = contentDescription; if (enabled) onClick { onAccessibilityClick(); true } }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val icon = when (emoji) {
            "↶" -> pro.perfectproduct.cramin.R.drawable.ic_action_undo
            "⏸" -> pro.perfectproduct.cramin.R.drawable.ic_action_pause
            else -> pro.perfectproduct.cramin.R.drawable.ic_action_play
        }
        androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(icon), null, tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
    }
}
