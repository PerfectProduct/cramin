package pro.perfectproduct.cramin.pipeline

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.perfectproduct.cramin.llm.Brief
import pro.perfectproduct.cramin.llm.ExtractResponse
import pro.perfectproduct.cramin.llm.GlossaryEntry
import pro.perfectproduct.cramin.llm.LlmJson
import pro.perfectproduct.cramin.llm.LlmRequest
import pro.perfectproduct.cramin.llm.ModelRole
import pro.perfectproduct.cramin.llm.Schemas
import pro.perfectproduct.cramin.llm.TranslateResponse
import pro.perfectproduct.cramin.testing.FakeLlmClient
import pro.perfectproduct.cramin.testing.Fixtures
import pro.perfectproduct.cramin.util.Lang

class MessagesAndFakeTest {
    private val segmenter = Segmenter(Icu4jSentenceBreaker())

    @Test
    fun translateMessageFormatMatchesSpec() {
        val brief = Brief("t", "📘", "Summary.", "libraries", "neutral", emptyList())
        val msg = Messages.translate(
            Lang.EN, Lang.RU, brief,
            listOf(GlossaryEntry("compute", "вычислительные мощности"), GlossaryEntry("frontier lab", "передовая лаборатория", "note")),
            listOf(ContextLine(118, 118, "Prev.", "Пред."), ContextLine(119, 120, "A. B.", "А. Б.")),
            listOf(SentenceDraft(121, 0, "Next one."), SentenceDraft(122, 0, "Last\none.")),
        )
        assertEquals(
            """
            SOURCE: en
            TARGET: ru
            BRIEF: Summary. | domain: libraries | register: neutral
            GLOSSARY:
            - compute → вычислительные мощности
            - frontier lab → передовая лаборатория (note: note)
            CONTEXT (already translated):
            [118] Prev. => Пред.
            [119-120] A. B. => А. Б.
            SENTENCES:
            [121] Next one.
            [122] Last one.
            """.trimIndent(),
            msg,
        )
        assertEquals(listOf(121, 122), Messages.parseSentences(msg).map { it.idx })
    }

    @Test
    fun extractMessageRoundTrips() {
        val pairs = listOf(
            SegmentPair(listOf(SentenceDraft(0, 0, "One."), SentenceDraft(1, 0, "Two.")), "Раз. Два."),
            SegmentPair(listOf(SentenceDraft(2, 0, "Three.")), "Три."),
        )
        val msg = Messages.extract(Lang.EN, Lang.RU, emptyList(), pairs)
        assertTrue(msg.contains("GLOSSARY:\n(none)\nPAIRS:\n[0] One.\n[1] Two.\n=> [0-1] Раз. Два.\n[2] Three.\n=> [2] Три."))
        val parsed = Messages.parsePairs(msg)
        assertEquals(pairs.map { it.sentences.map { s -> s.idx } }, parsed.map { it.sentences.map { s -> s.idx } })
        assertEquals(pairs.map { it.translation }, parsed.map { it.translation })
    }

    @Test
    fun fakeTranslatesWithMergedSegmentsThatValidate() = runTest {
        val fake = FakeLlmClient()
        val sentences = segmenter.segment(Fixtures.text(Lang.EN), Lang.EN)
        val user = Messages.translate(Lang.EN, Lang.RU, null, emptyList(), emptyList(), sentences)
        val resp = fake.complete(LlmRequest(ModelRole.TRANSLATE, "fake/model", "sys", user, Schemas.TRANSLATE_NAME, Schemas.TRANSLATE, 0.3, null))
        val segs = LlmJson.parse<TranslateResponse>(resp.content).seg
        val v = TranslationValidator.validate(sentences.first().idx..sentences.last().idx, segs)
        assertTrue(v.isComplete)
        assertEquals(0, v.rejected)
        assertTrue("есть объединённые сегменты", segs.any { it.to > it.from })
        assertTrue(segs.all { it.to - it.from <= 2 })
    }

    @Test
    fun fakeExtractsPolysemyAndStopProbe() = runTest {
        val fake = FakeLlmClient()
        val pairs = listOf(
            SegmentPair(listOf(SentenceDraft(0, 0, "The bank was closed.")), FakeLlmClient.fakeTranslate("The bank was closed.")),
            SegmentPair(listOf(SentenceDraft(1, 0, "We sat on the river bank.")), FakeLlmClient.fakeTranslate("We sat on the river bank.")),
        )
        val user = Messages.extract(Lang.EN, Lang.RU, emptyList(), pairs)
        val resp = fake.complete(LlmRequest(ModelRole.EXTRACT, "fake/model", "sys", user, Schemas.EXTRACT_NAME, Schemas.EXTRACT, 0.1, null))
        val units = LlmJson.parse<ExtractResponse>(resp.content).u
        val bank = units.filter { it.l == "bank" }
        assertEquals(setOf("банк", "берег"), bank.map { it.g }.toSet())
        assertTrue(units.any { it.l == "the" })
        assertEquals("VERB", units.first { it.l == "closed" }.p)
        assertTrue(units.all { it.ft == "tr_${it.l}" })
    }
}
