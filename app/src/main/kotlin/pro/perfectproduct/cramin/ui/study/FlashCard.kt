package pro.perfectproduct.cramin.ui.study

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pro.perfectproduct.cramin.R
import pro.perfectproduct.cramin.app.theme.study
import pro.perfectproduct.cramin.data.db.Direction
import pro.perfectproduct.cramin.data.repo.StudyCard
import pro.perfectproduct.cramin.data.repo.StudyExample
import pro.perfectproduct.cramin.ui.components.contentTextStyle
import pro.perfectproduct.cramin.ui.components.posLabel
import pro.perfectproduct.cramin.util.Lang
import kotlin.math.abs

/**
 * Карточка сессии (SPEC §9.6): тап — 3D-переворот по оси Y (300 мс); горизонтальный свайп с порогом 30 %
 * ширины или быстрым флингом; при перетаскивании карточка наклоняется, края подсвечиваются зелёным/оранжевым.
 */
@Composable
fun FlashCard(
    card: StudyCard,
    direction: Direction,
    flipped: Boolean,
    ttsLangs: Set<Lang>,
    onFlip: () -> Unit,
    onSwipeRight: () -> Unit,
    onSwipeLeft: () -> Unit,
    onSpeak: (String, Lang) -> Unit,
    onStar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val flipLabel = stringResource(R.string.card_flip)
    val knownLabel = stringResource(R.string.cards_known)
    val learningLabel = stringResource(R.string.cards_learning)
    val scope = rememberCoroutineScope()
    val offsetX = remember(card.id) { Animatable(0f) }
    val density = LocalDensity.current
    val rotation by animateFloatAsState(targetValue = if (flipped) 180f else 0f, animationSpec = tween(FLIP_MS), label = "flip")
    val studyColors = MaterialTheme.study
    var widthPx = 1f

    // Жесты ловим до graphicsLayer: иначе после переворота (rotationY = 180°) координаты зеркалятся,
    // и свайп вправо читался бы как свайп влево.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .testTag("flashCard")
            .semantics {
                onClick(label = flipLabel) { onFlip(); true }
                customActions = listOf(
                    CustomAccessibilityAction(learningLabel) { onSwipeLeft(); true },
                    CustomAccessibilityAction(knownLabel) { onSwipeRight(); true },
                )
            }
            .pointerInput(card.id) {
                detectTapGestures(onTap = { onFlip() })
            }
            .pointerInput(card.id) {
                val tracker = VelocityTracker()
                detectHorizontalDragGestures(
                    onDragStart = { tracker.resetTracking() },
                    onDragEnd = {
                        val velocity = tracker.calculateVelocity().x
                        val threshold = size.width * SWIPE_THRESHOLD
                        val target = when {
                            offsetX.value > threshold || velocity > FLING_VELOCITY -> 1
                            offsetX.value < -threshold || velocity < -FLING_VELOCITY -> -1
                            else -> 0
                        }
                        scope.launch {
                            if (target == 0) {
                                offsetX.animateTo(0f, tween(200))
                            } else {
                                offsetX.animateTo(target * size.width * 1.5f, tween(220))
                                if (target > 0) onSwipeRight() else onSwipeLeft()
                            }
                        }
                    },
                    onDragCancel = { scope.launch { offsetX.animateTo(0f, tween(200)) } },
                    onHorizontalDrag = { change, dragAmount ->
                        tracker.addPosition(change.uptimeMillis, change.position)
                        change.consume()
                        scope.launch { offsetX.snapTo(offsetX.value + dragAmount) }
                    },
                )
            }
            .graphicsLayer {
                widthPx = size.width.coerceAtLeast(1f)
                translationX = offsetX.value
                rotationZ = (offsetX.value / widthPx) * MAX_TILT_DEGREES
                rotationY = rotation
                cameraDistance = 12f * density.density
            },
    ) {
        val progress = (abs(offsetX.value) / (widthPx * SWIPE_THRESHOLD)).coerceIn(0f, 1f)
        val edge = if (offsetX.value > 0) studyColors.known else studyColors.learning
        val showBack = rotation > 90f
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(3.dp, edge.copy(alpha = progress * 0.9f), RoundedCornerShape(28.dp))
                // Оборот рисуется зеркально, чтобы после поворота на 180° читаться нормально.
                .graphicsLayer { if (showBack) rotationY = 180f },
        ) {
            if (!showBack) {
                CardFace(card, direction, isFront = true, ttsLangs, onSpeak, onStar)
            } else {
                CardFace(card, direction, isFront = false, ttsLangs, onSpeak, onStar)
            }
            if (progress > 0f) {
                Box(Modifier.fillMaxSize().background(edge.copy(alpha = progress * 0.12f)))
            }
        }
    }
}

@Composable
private fun CardFace(card: StudyCard, direction: Direction, isFront: Boolean, ttsLangs: Set<Lang>, onSpeak: (String, Lang) -> Unit, onStar: () -> Unit) {
    val showSource = (direction == Direction.SRC_FRONT) == isFront
    val speakLang = if (showSource) card.lang else card.targetLang
    val speakText = if (showSource) (card.lemmaVocalized ?: card.lemma) else card.visibleSenses.joinToString("; ") { it.translation }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (speakLang in ttsLangs) {
                IconButton(onClick = { onSpeak(speakText, speakLang) }, modifier = Modifier.testTag("speak")) { Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_action_volume), stringResource(R.string.action_speak)) }
            } else {
                Spacer(Modifier.height(48.dp))
            }
            IconButton(onClick = onStar, modifier = Modifier.testTag("star")) {
                Icon(
                    if (card.starred) Icons.Filled.Star else Icons.Outlined.Star,
                    contentDescription = stringResource(R.string.word_star),
                    tint = if (card.starred) MaterialTheme.study.starred else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (showSource && isFront) {
                Column(Modifier.verticalScroll(rememberScrollState())) { SourceSide(card) }
            } else if (!showSource && isFront) {
                Text(
                    card.visibleSenses.joinToString("; ") { it.translation },
                    style = contentTextStyle(MaterialTheme.typography.headlineMedium),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.testTag("cardFront").verticalScroll(rememberScrollState()),
                )
            } else {
                BackSide(card, showSource)
            }
        }
    }
}

@Composable
private fun SourceSide(card: StudyCard) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.testTag("cardFront")) {
        if (card.lemmaVocalized != null) {
            Text(card.lemmaVocalized, style = contentTextStyle(MaterialTheme.typography.bodyLarge), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(card.lemma, style = contentTextStyle(MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold)), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(posLabel(card.pos), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Оборот: одно значение и его пример; при TGT_FRONT — лемма и тот же пример. Прокручивается. */
@Composable
private fun BackSide(card: StudyCard, showSource: Boolean) {
    val highlight = MaterialTheme.colorScheme.primary
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("cardBack"), horizontalAlignment = Alignment.CenterHorizontally) {
        if (showSource) {
            if (card.lemmaVocalized != null) Text(card.lemmaVocalized, style = contentTextStyle(MaterialTheme.typography.bodyMedium), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(card.lemma, style = contentTextStyle(MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold)))
            Text(posLabel(card.pos), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
        }
        for (sense in card.visibleSenses) {
            if (!showSource) {
                Text(sense.translation, style = contentTextStyle(MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold)), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            sense.example?.let { ex -> ExampleBlock(ex, highlight) }
            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
private fun ExampleBlock(ex: StudyExample, highlight: Color) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 18.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp)) {
        Text(highlighted(ex.sentence, ex.start, ex.end, highlight), style = contentTextStyle(MaterialTheme.typography.bodyMedium))
        if (!ex.translation.isNullOrBlank()) {
            if (ex.sentence.isNotBlank()) androidx.compose.material3.HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp).testTag("exampleDivider"),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Text(
                highlighted(ex.translation, ex.targetStart, ex.targetEnd, highlight),
                style = contentTextStyle(MaterialTheme.typography.bodyMedium), color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun highlighted(text: String, start: Int?, end: Int?, color: Color) = buildAnnotatedString {
    if (start == null || end == null || start < 0 || end > text.length || start >= end) {
        append(text)
    } else {
        append(text.substring(0, start))
        withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) { append(text.substring(start, end)) }
        append(text.substring(end))
    }
}

private const val FLIP_MS = 300
private const val SWIPE_THRESHOLD = 0.3f
private const val FLING_VELOCITY = 2500f
private const val MAX_TILT_DEGREES = 12f
