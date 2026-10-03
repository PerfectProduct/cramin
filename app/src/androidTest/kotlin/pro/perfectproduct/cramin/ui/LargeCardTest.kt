package pro.perfectproduct.cramin.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pro.perfectproduct.cramin.app.theme.CraminTheme
import pro.perfectproduct.cramin.data.db.*
import pro.perfectproduct.cramin.data.repo.*
import pro.perfectproduct.cramin.ui.study.FlashCard
import pro.perfectproduct.cramin.util.Lang

@RunWith(AndroidJUnit4::class)
class LargeCardTest {
    @get:Rule val compose = createComposeRule()
    @Test fun hebrewFont200ScrollDoesNotFlipOrSort() = check(2f, Lang.HE, Direction.SRC_FRONT, true)
    @Test fun englishFont150ReverseScrollKeepsActions() = check(1.5f, Lang.EN, Direction.TGT_FRONT, false)

    private fun check(scale: Float, lang: Lang, direction: Direction, dark: Boolean) {
        var flips = 0
        var sorts = 0
        val example = if (lang == Lang.HE) "הַסֵּפֶר הַחָדָשׁ מֻנָּח עַל הַשֻּׁלְחָן. " else "The book is waiting on the library table. "
        val card = StudyCard(1, 1, "book|NOUN", if (lang == Lang.HE) "ספר" else "book", if (lang == Lang.HE) "סֵפֶר" else null,
            Pos.NOUN, lang, Lang.RU, CardStatus.NEW, false, 0,
            listOf(StudySense(1, "книга", StudyExample(0, example.repeat(30), 0, 4,
                "Новая книга лежит на столе в библиотеке. ".repeat(30), 6, 11))), meaningKey = "книга")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                CraminTheme(darkTheme = dark) {
                    Column(Modifier.width(320.dp).height(640.dp)) {
                        FlashCard(card, direction, true, emptySet(), { flips++ }, { sorts++ }, { sorts++ }, { _, _ -> }, {}, Modifier.weight(1f))
                        Text("Основные действия", modifier = Modifier.testTag("fixedActions"))
                    }
                }
            }
        }
        compose.onNodeWithTag("cardBack").assertIsDisplayed()
        repeat(3) { compose.onNodeWithTag("cardBack").performTouchInput { swipeUp() } }
        compose.onNodeWithTag("fixedActions").assertIsDisplayed()
        compose.onNodeWithTag("cardBack").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, flips); assertEquals(0, sorts) }
    }
}
