package pro.perfectproduct.cramin.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import pro.perfectproduct.cramin.R
import pro.perfectproduct.cramin.data.db.Pos
import pro.perfectproduct.cramin.pipeline.ErrorCode
import pro.perfectproduct.cramin.util.Lang

/** Направление текста по содержимому (SPEC §3): иврит справа налево внутри LTR-макета. */
@Composable
fun contentTextStyle(base: androidx.compose.ui.text.TextStyle) = base.copy(textDirection = TextDirection.Content)

@Composable
fun langLabel(lang: Lang): String = stringResource(
    when (lang) {
        Lang.EN -> R.string.lang_en
        Lang.RU -> R.string.lang_ru
        Lang.HE -> R.string.lang_he
    },
)

@Composable
fun posLabel(pos: Pos): String = stringResource(
    when (pos) {
        Pos.NOUN -> R.string.pos_noun
        Pos.VERB -> R.string.pos_verb
        Pos.ADJ -> R.string.pos_adj
        Pos.ADV -> R.string.pos_adv
        Pos.PHRASAL_VERB -> R.string.pos_phrasal_verb
        Pos.IDIOM -> R.string.pos_idiom
        Pos.COLLOCATION -> R.string.pos_collocation
    },
)

/** Действие, которое предлагается пользователю по коду ошибки (SPEC: конкретные ошибки с действием). */
enum class ErrorAction { RETRY, OPEN_SETTINGS, CHOOSE_SOURCE_LANG, CHOOSE_TARGET_LANG, CHECK_UPDATES }

fun errorMessageRes(code: ErrorCode): Int = when (code) {
    ErrorCode.NO_KEY -> R.string.err_no_key
    ErrorCode.AUTH -> R.string.err_auth
    ErrorCode.PAYMENT -> R.string.err_payment
    ErrorCode.NETWORK -> R.string.err_network
    ErrorCode.RATE_LIMIT -> R.string.err_rate_limit
    ErrorCode.SERVER -> R.string.err_server
    ErrorCode.BAD_REQUEST -> R.string.err_bad_request
    ErrorCode.INVALID_RESPONSE -> R.string.err_invalid_response
    ErrorCode.LANG_UNDETECTED -> R.string.err_lang_undetected
    ErrorCode.SAME_LANGUAGE -> R.string.err_same_language
    ErrorCode.EMPTY_TEXT -> R.string.err_empty_text
    ErrorCode.ARTICLE_EXTRACT -> R.string.err_article
    ErrorCode.PDF_INVALID -> R.string.err_pdf_invalid
    ErrorCode.PDF_ENCRYPTED -> R.string.err_pdf_encrypted
    ErrorCode.PDF_NO_TEXT -> R.string.err_pdf_no_text
    ErrorCode.YOUTUBE_RESTRICTED -> R.string.err_youtube_restricted
    ErrorCode.YOUTUBE_FORMAT -> R.string.err_youtube_format
    ErrorCode.YOUTUBE_NO_LANG -> R.string.err_youtube_no_lang
    ErrorCode.TRANSCRIPTION -> R.string.err_transcription
    ErrorCode.STORAGE -> R.string.err_storage
    ErrorCode.UNKNOWN -> R.string.err_unknown
}

fun errorActions(code: ErrorCode): List<ErrorAction> = when (code) {
    ErrorCode.NO_KEY, ErrorCode.AUTH, ErrorCode.PAYMENT, ErrorCode.BAD_REQUEST -> listOf(ErrorAction.OPEN_SETTINGS, ErrorAction.RETRY)
    ErrorCode.LANG_UNDETECTED, ErrorCode.YOUTUBE_NO_LANG -> listOf(ErrorAction.CHOOSE_SOURCE_LANG)
    ErrorCode.SAME_LANGUAGE -> listOf(ErrorAction.CHOOSE_TARGET_LANG)
    ErrorCode.YOUTUBE_FORMAT -> listOf(ErrorAction.CHECK_UPDATES, ErrorAction.RETRY)
    ErrorCode.INVALID_RESPONSE -> listOf(ErrorAction.RETRY, ErrorAction.OPEN_SETTINGS)
    else -> listOf(ErrorAction.RETRY)
}

@Composable
fun errorActionLabel(action: ErrorAction): String = stringResource(
    when (action) {
        ErrorAction.RETRY -> R.string.action_retry
        ErrorAction.OPEN_SETTINGS -> R.string.action_open_settings
        ErrorAction.CHOOSE_SOURCE_LANG -> R.string.action_choose_source_lang
        ErrorAction.CHOOSE_TARGET_LANG -> R.string.action_choose_target_lang
        ErrorAction.CHECK_UPDATES -> R.string.action_check_updates
    },
)

/** Сегментированный выбор языка «[RU] [EN] [HE]» (SPEC §9.2). */
@Composable
fun LangSelector(selected: Lang?, onSelect: (Lang) -> Unit, modifier: Modifier = Modifier, order: List<Lang> = listOf(Lang.RU, Lang.EN, Lang.HE), enabled: (Lang) -> Boolean = { true }) {
    Row(modifier = modifier, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        for (lang in order) {
            FilterChip(selected = lang == selected, onClick = { onSelect(lang) }, enabled = enabled(lang), label = { Text(lang.label) })
        }
    }
}

@Composable
fun EmojiBadge(emoji: String, size: Int = 28, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size((size + 16).dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, style = MaterialTheme.typography.headlineSmall.copy(fontSize = androidx.compose.ui.unit.TextUnit(size.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)))
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = modifier.padding(top = 20.dp, bottom = 8.dp))
}

@Composable
fun EmptyState(emoji: String, text: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(emoji, style = MaterialTheme.typography.displaySmall)
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

fun formatUsd(value: Double?): String = if (value == null) "—" else if (value < 0.01) "%.4f".format(value) else "%.2f".format(value)

fun formatDate(epochMs: Long): String {
    val fmt = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.forLanguageTag("ru"))
    return fmt.format(java.util.Date(epochMs)).replace(".", "")
}
