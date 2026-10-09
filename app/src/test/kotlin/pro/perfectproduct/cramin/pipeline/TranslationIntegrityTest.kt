package pro.perfectproduct.cramin.pipeline

import org.junit.Assert.*
import org.junit.Test
import pro.perfectproduct.cramin.llm.*

class TranslationIntegrityTest {
    private val source = listOf(SentenceDraft(58, 0, "### 7. Датчик работает в 80% случаев."),
        SentenceDraft(59, 0, "Проверка `sensor_id` описана на https://example.org/manual."))
    private fun segment(from: Int, to: Int, t: String) = TranslatedSegment(from, to, t,
        source.filter { it.idx in from..to }.map(TranslationIntegrity::sourceId))
    private fun reject(segments: List<TranslatedSegment>, proof: Boolean = true) {
        try { TranslationIntegrity.check(source, segments, proof); fail("Expected integrity violation") }
        catch (e: LlmException.InvalidResponse) { assertTrue(e.reason.startsWith("translation integrity:")) }
    }

    @Test fun correctAndMergedTranslationsPreserveOrderedAnchors() {
        val s = segment(58, 59, "### 7. The sensor works in 80% of cases. See `sensor_id` at https://example.org/manual.")
        assertEquals(TranslationIntegrity.Evidence.MECHANICALLY_VERIFIED, TranslationIntegrity.check(source, listOf(s), true))
        assertEquals(TranslationIntegrity.Evidence.MECHANICALLY_VERIFIED, TranslationIntegrity.check(source, listOf(
            segment(58, 58, "### 7. The sensor works in 80% of cases."),
            segment(59, 59, "See `sensor_id` at https://example.org/manual.")), true))
        val three = source + SentenceDraft(60, 0, "Шлюз закрыт.")
        assertEquals(TranslationIntegrity.Evidence.MECHANICALLY_VERIFIED, TranslationIntegrity.check(three,
            listOf(TranslatedSegment(58, 60, s.t + " The gate is closed.", three.map(TranslationIntegrity::sourceId))), true))
        assertEquals(listOf("80.5", "`counter_2`", "HTTPS://example.org/v3"),
            TranslationIntegrity.anchors("80.5 percent, `counter_2`, HTTPS://example.org/v3."))
    }

    @Test fun foreignAnchorsRejectedEvenWithCorrectIds() {
        reject(listOf(segment(58, 58, "### 9. The sensor works in 80% of cases.")))
        reject(listOf(segment(58, 58, "### 7. The sensor works in 20% of cases.")))
        reject(listOf(segment(59, 59, "See `valve_id` at https://example.org/manual.")))
        reject(listOf(segment(59, 59, "See `sensor_id` at https://example.org/foreign.")))
        reject(listOf(segment(58, 58, "### 7. It works.")))
        reject(listOf(segment(58, 58, "### 7. It works in 80% of 999 cases.")))
        reject(listOf(segment(58, 58, "80% of cases. ### 7. The sensor.")))
    }

    @Test fun missingSwappedOrChangedSourceIdentityRejected() {
        val good = segment(58, 58, "### 7. The sensor works in 80% of cases.")
        reject(listOf(good.copy(sourceIds = null)))
        reject(listOf(good.copy(sourceIds = listOf(TranslationIntegrity.sourceId(source[1])))))
        reject(listOf(good.copy(sourceIds = listOf(TranslationIntegrity.sourceId(source[0].copy(text = "Other source"))))))
        val merged = segment(58, 59, "7 80 `sensor_id` https://example.org/manual")
        reject(listOf(merged.copy(sourceIds = merged.sourceIds!!.reversed())))
    }

    @Test fun orderScopeOverlapSpanAndGappedRequestsRejected() {
        val a = segment(58, 58, "7 80"); val b = segment(59, 59, "`sensor_id` https://example.org/manual")
        reject(listOf(b, a)); reject(listOf(a, a)); reject(listOf(a.copy(from = 57, to = 57)))
        reject(listOf(a.copy(to = 61))); reject(listOf(a.copy(to = Int.MAX_VALUE)))
        try {
            TranslationIntegrity.check(listOf(source[0], source[1].copy(idx = 60)), listOf(segment(58, 60, "7 80")), true)
            fail("Cannot merge through a sentence absent from the request")
        } catch (_: LlmException.InvalidResponse) { }
    }

    @Test fun oldCacheWithoutEvidenceIsUnknownAndReadable() {
        val stored = LlmJson.parse<StoredTranslation>("""{"seg":[{"from":58,"to":58,"t":"The sensor works in eighty percent of cases."}]}""")
        assertNull(stored.integrityVersion); assertNull(stored.seg[0].sourceIds)
        assertEquals(TranslationIntegrity.Evidence.UNKNOWN, TranslationIntegrity.check(source, stored.seg, false))
        reject(stored.seg)
        reject(listOf(TranslatedSegment(58, 58, "### 9. Foreign heading")), proof = false)
    }

    @Test fun echoAndMatchingAnchorsDoNotProveSemantics() {
        // A deliberate boundary: wrong meaning with the same anchors is not claimed to be detectable.
        val unrelated = segment(58, 58, "### 7. The library opens in 80% of cases.")
        assertEquals(TranslationIntegrity.Evidence.MECHANICALLY_VERIFIED, TranslationIntegrity.check(source, listOf(unrelated), true))
    }

    @Test fun plannerBoundsShortSentencesWithoutRenumbering() {
        val sentences = (120..200).map { SentenceDraft(it, 0, "Короткая строка.") }
        val plan = SectionPlanner.plan(sentences, 4000)
        assertEquals((120..200).toList(), plan.flatMap { it.toList() })
        assertTrue(plan.all { it.count() <= SectionPlanner.MAX_SECTION_SENTENCES })
        assertTrue(plan.size >= 3)
    }
}
