package pro.perfectproduct.cramin.ui.document

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import pro.perfectproduct.cramin.R
import pro.perfectproduct.cramin.data.repo.StudyCard
import pro.perfectproduct.cramin.ui.components.EmptyState
import pro.perfectproduct.cramin.ui.components.contentTextStyle

/** Вкладка «Текст» (SPEC §9.4): абзацы → сегменты, подчёркнутые слова, режимы Пары/Оригинал/Перевод. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TextTab(vm: DocumentViewModel, header: @Composable () -> Unit = {}) {
    val state by vm.reading.collectAsState()
    val mode by vm.viewMode.collectAsState()
    var sheetCard by remember { mutableStateOf<StudyCard?>(null) }
    var loadingCardId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(loadingCardId) {
        val id = loadingCardId ?: return@LaunchedEffect
        sheetCard = vm.card(id)
        loadingCardId = null
    }

    TextContent(state, mode, vm::setViewMode, vm::retryReading, header) { loadingCardId = it }

    sheetCard?.let { card ->
        ModalBottomSheet(onDismissRequest = { sheetCard = null }) {
            WordSheet(card = card, vm = vm, onChanged = { updated -> sheetCard = updated })
        }
    }
}

@Composable
internal fun TextContent(
    state: ReadingState,
    mode: TextViewMode,
    onMode: (TextViewMode) -> Unit,
    onRetry: () -> Unit,
    header: @Composable () -> Unit = {},
    onWordClick: (Long) -> Unit = {},
) {
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(28.dp), modifier = Modifier.testTag("textList")) {
        item(key = "documentHeader") {
            Column {
                header()
                pro.perfectproduct.cramin.ui.components.SingleChoice(TextViewMode.entries, mode, { stringResource(when(it) { TextViewMode.PAIRS -> R.string.doc_view_pairs; TextViewMode.SOURCE_ONLY -> R.string.doc_view_source; TextViewMode.TARGET_ONLY -> R.string.doc_view_target }) }, onMode, Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            }
        }
        if (state.error) item(key = "error") {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(stringResource(R.string.doc_text_error))
                androidx.compose.material3.TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            }
        }
        if (state.loading) {
            item(key = "loading") { Text(stringResource(R.string.doc_text_loading), Modifier.padding(20.dp).testTag("readingLoading")) }
        } else if (state.paragraphs.isEmpty() && !state.error) {
            item(key = "empty") { EmptyState("📄", stringResource(R.string.doc_text_empty)) }
        } else {
            items(state.paragraphs, key = { it.paragraphIdx }) { paragraph ->
                Column(modifier = Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    for (segment in paragraph.segments) {
                        Column {
                            if (mode != TextViewMode.TARGET_ONLY) {
                                UnderlinedSentences(segment.sentences, onWordClick = { onWordClick(it) })
                            }
                            if (mode != TextViewMode.SOURCE_ONLY && segment.translation != null) {
                                Text(
                                    segment.translation,
                                    style = contentTextStyle(MaterialTheme.typography.bodyMedium),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = if (mode == TextViewMode.PAIRS) 12.dp else 0.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

}

/** Предложения сегмента одним текстом; слова с карточками подчёркнуты пунктиром и кликабельны. */
@Composable
private fun UnderlinedSentences(sentences: List<TextSentence>, onWordClick: (Long) -> Unit) {
    val color = MaterialTheme.colorScheme.primary
    val annotated = remember(sentences) {
        buildAnnotatedString {
            for ((i, s) in sentences.withIndex()) {
                if (i > 0) append(' ')
                var pos = 0
                for (span in s.spans) {
                    if (span.start < pos || span.end > s.text.length) continue
                    append(s.text.substring(pos, span.start))
                    withLink(LinkAnnotation.Clickable(tag = "card:${span.cardId}", styles = TextLinkStyles(style = SpanStyle(color = color))) { onWordClick(span.cardId) }) {
                        append(s.text.substring(span.start, span.end))
                    }
                    pos = span.end
                }
                append(s.text.substring(pos))
            }
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = annotated,
        style = contentTextStyle(MaterialTheme.typography.bodyLarge),
        onTextLayout = { layout = it },
        modifier = Modifier.fillMaxWidth().drawBehind { drawDottedUnderlines(annotated, layout, color) },
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDottedUnderlines(text: AnnotatedString, layout: TextLayoutResult?, color: Color) {
    layout ?: return
    val effect = PathEffect.dashPathEffect(floatArrayOf(3f, 4f), 0f)
    for (range in text.getLinkAnnotations(0, text.length)) {
        val startLine = layout.getLineForOffset(range.start)
        val endLine = layout.getLineForOffset(maxOf(range.start, range.end - 1))
        for (line in startLine..endLine) {
            val from = if (line == startLine) layout.getHorizontalPosition(range.start, usePrimaryDirection = true) else layout.getLineLeft(line)
            val to = if (line == endLine) layout.getHorizontalPosition(range.end, usePrimaryDirection = true) else layout.getLineRight(line)
            val y = layout.getLineBottom(line) - 2f
            drawLine(color, Offset(minOf(from, to), y), Offset(maxOf(from, to), y), strokeWidth = 2f, pathEffect = effect, cap = StrokeCap.Round)
        }
    }
}
