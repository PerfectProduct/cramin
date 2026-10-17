package pro.perfectproduct.cramin.study

import org.junit.Assert.*
import org.junit.Test
import pro.perfectproduct.cramin.data.db.*
import pro.perfectproduct.cramin.data.repo.*
import pro.perfectproduct.cramin.util.Lang

class StudyCardContentTest {
    private val example = StudyExample(7, "They sat on the bank of the river.", 16, 20,
        "Они сидели на берегу реки. Потом пошли домой.", 14, 20, "bank", "берегу")
    private val card = StudyCard(1, 1, "bank|NOUN", "bank", null, Pos.NOUN, Lang.EN, Lang.RU,
        CardStatus.NEW, false, 7, listOf(StudySense(1, "берег", example), StudySense(2, "банк", null)))

    @Test fun fourFacesUseOnlyTheSelectedLanguageAndExample() {
        for (direction in Direction.entries) for (front in listOf(true, false)) {
            val face = card.content(direction, front)
            val source = (direction == Direction.SRC_FRONT) == front
            assertEquals(source, face.showSource)
            assertEquals(if (source) "bank" else "берег", face.word)
            assertEquals(if (source) example.sentence else example.translation, face.context)
            assertEquals(if (source) "bank" else "берегу", requireNotNull(face.context).substring(requireNotNull(face.span).start, face.span.end))
            assertEquals(if (source) Lang.EN else Lang.RU, face.language)
            assertEquals(if (source) "bank" else "берег", face.speech)
        }
    }
    @Test fun missingExampleAndEmptySensesKeepWordAndSpeech() {
        val noExample = card.copy(senses = listOf(StudySense(1, "берег", null)))
        for (direction in Direction.entries) for (front in listOf(true, false)) {
            val face = noExample.content(direction, front)
            assertNull(face.context); assertNull(face.span)
            assertTrue(face.word in listOf("bank", "берег"))
        }
        assertNull(card.copy(senses = emptyList()).content(Direction.TGT_FRONT, true).context)
    }
    @Test fun absentTranslationNeverFallsBackToSource() {
        for (translation in listOf(null, "", "  ")) {
            val c = card.copy(senses = listOf(StudySense(1, "берег", example.copy(translation = translation))))
            assertNull(c.content(Direction.TGT_FRONT, true).context)
            assertEquals(example.sentence, c.content(Direction.SRC_FRONT, true).context)
        }
    }
    @Test fun partialBindingsKeepTextWithoutSpan() {
        val c = card.copy(senses = listOf(StudySense(1, "берег", example.copy(end = null, targetSurface = null))))
        for (direction in Direction.entries) {
            val face = c.content(direction, true)
            assertNotNull(face.context); assertNull(face.span)
        }
    }
    private fun span(text: String, start: Int?, end: Int?, surface: String?, lang: Lang = Lang.EN, unique: Boolean = false) =
        savedStudySpan(text, start, end, surface, lang, unique)
    @Test fun wholeExpressionAndInflectedForm() {
        assertEquals(StudySpan(5, 12), span("They gave up today.", 5, 12, "gave up"))
        assertEquals(StudySpan(14, 20), span(requireNotNull(example.translation), 14, 20, "берегу", Lang.RU, true))
        assertNull(span(example.translation, 14, 20, "берег", Lang.RU, true))
    }
    @Test fun repeatedSourceUsesSavedSecondOccurrenceButTargetIsAmbiguous() {
        val text = "bank then bank"
        assertEquals(StudySpan(10, 14), span(text, 10, 14, "bank"))
        assertNull(span(text, 10, 14, "bank", unique = true))
        assertNull(span(text, 5, 9, "bank"))
        assertNull(span("banking bank", 0, 4, "bank"))
    }
    @Test fun badRangesAndMissingOrMismatchedFormsNeverHighlight() {
        for ((start, end) in listOf(null to null, null to 4, 0 to null, -1 to 4, 0 to 8, 3 to 2, 0 to 0))
            assertNull(span("bank", start, end, "bank"))
        for (form in listOf(null, "", " ", "shore")) assertNull(span("bank", 0, 4, form))
    }
    @Test fun emojiUtf16AndSurrogateBoundaries() {
        val text = "😀 bank"
        assertEquals(StudySpan(3, 7), span(text, 3, 7, "bank"))
        assertNull(span(text, 2, 6, "bank"))
        assertNull(span(text, 0, 1, "😀"))
        assertNull(span(text, 1, 2, "😀"))
        assertEquals(StudySpan(0, 2), span(text, 0, 2, "😀"))
    }
    @Test fun emojiGraphemesMustNotBeSplit() {
        for (emoji in listOf("👩‍💻", "👍🏽", "🇷🇺")) {
            assertEquals(StudySpan(0, emoji.length), span(emoji, 0, emoji.length, emoji))
            assertNull(span(emoji, 0, 2, emoji.substring(0, 2)))
        }
    }
    @Test fun canonicalCombiningMarksAreKeptWhole() {
        val text = "A cafe\u0301 nearby"
        assertEquals(StudySpan(2, 7), span(text, 2, 7, "café"))
        assertNull(span(text, 2, 6, "café"))
        assertNull(span(text, 6, 7, "\u0301"))
    }
    @Test fun hebrewVocalizationAndPrefixesAreMappedToOriginal() {
        val text = "הַסֵּפֶר עַל הַשֻּׁלְחָן."
        val end = text.indexOf(' ')
        assertEquals(StudySpan(2, end), span(text, 2, end, "ספר", Lang.HE))
        assertEquals("סֵּפֶר", text.substring(2, end))
        assertNull(span(text, 2, end - 1, "ספר", Lang.HE))
        assertNull(span(text, 3, end, "ספר", Lang.HE))
    }
    @Test fun vocalizedSpeechStillExcludesContext() {
        val c = card.copy(lang = Lang.HE, lemma = "ספר", lemmaVocalized = "סֵפֶר")
        for (direction in Direction.entries) for (front in listOf(true, false)) {
            val face = c.content(direction, front)
            assertEquals(if (face.showSource) "סֵפֶר" else "берег", face.speech)
        }
    }
}
