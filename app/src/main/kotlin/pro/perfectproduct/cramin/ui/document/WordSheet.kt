package pro.perfectproduct.cramin.ui.document

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pro.perfectproduct.cramin.R
import pro.perfectproduct.cramin.app.theme.study
import pro.perfectproduct.cramin.data.db.CardStatus
import pro.perfectproduct.cramin.data.repo.StudyCard
import pro.perfectproduct.cramin.ui.components.contentTextStyle
import pro.perfectproduct.cramin.ui.components.posLabel

/** Мини-карточка слова (SPEC §9.4): лемма (иврит с огласовками), часть речи, смыслы, статус, звезда, 🔊. */
@Composable
fun WordSheet(card: StudyCard, vm: DocumentViewModel, onChanged: (StudyCard) -> Unit) {
    val tts by vm.ttsAvailable.collectAsState()
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                if (card.lemmaVocalized != null) {
                    Text(card.lemmaVocalized, style = contentTextStyle(MaterialTheme.typography.bodyMedium), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(card.lemma, style = contentTextStyle(MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold)))
                Text(posLabel(card.pos), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (card.lang in tts) {
                IconButton(onClick = { vm.speak(card.lemmaVocalized ?: card.lemma, card.lang) }) { Text("🔊") }
            }
            IconButton(onClick = { vm.setStarred(card.id, !card.starred); onChanged(card.copy(starred = !card.starred)) }) {
                Icon(
                    if (card.starred) Icons.Filled.Star else Icons.Outlined.Star,
                    contentDescription = stringResource(R.string.word_star),
                    tint = if (card.starred) MaterialTheme.study.starred else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        for (sense in card.visibleSenses) {
            Text("• " + sense.translation, style = contentTextStyle(MaterialTheme.typography.bodyLarge))
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip(stringResource(R.string.word_status_new), card.status == CardStatus.NEW) { vm.setStatus(card.id, CardStatus.NEW); onChanged(card.copy(status = CardStatus.NEW)) }
            StatusChip(stringResource(R.string.word_status_learning), card.status == CardStatus.LEARNING) { vm.setStatus(card.id, CardStatus.LEARNING); onChanged(card.copy(status = CardStatus.LEARNING)) }
            StatusChip(stringResource(R.string.word_status_known), card.status == CardStatus.KNOWN) { vm.setStatus(card.id, CardStatus.KNOWN); onChanged(card.copy(status = CardStatus.KNOWN)) }
        }
    }
}

@Composable
private fun StatusChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}
