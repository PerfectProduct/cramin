package pro.perfectproduct.cramin.pipeline

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import pro.perfectproduct.cramin.llm.PipelineParams

class ExtractChunkCoverageTest {
    /** Only numeric shapes are retained; no owner document text, URL or model reply is committed. */
    @Test fun savedShapesHave18WholeSegmentChunksAndCoverEverySentenceAndRawUnitOnce() {
        val fixture = requireNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/extract-plan-shapes.json"))
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        val cases = fixture.getValue("cases").jsonArray
        assertEquals(2, cases.size)
        for (entry in cases) {
            val shape = entry.jsonObject
            val name = shape.getValue("name").jsonPrimitive.content
            val spans = shape.getValue("segments").jsonArray.map { row ->
                val values = row.jsonArray.map { it.jsonPrimitive.int }
                SegmentSpan(values[0], values[1], values[2])
            }
            val sentenceCount = shape.getValue("sentenceCount").jsonPrimitive.int
            // Distinct ordinal identities preserve multiplicity of repeated units at the same i.
            val units = shape.getValue("rawUnitsPerSentence").jsonArray.flatMapIndexed { idx, count ->
                List(count.jsonPrimitive.int) { idx }
            }.mapIndexed { ordinal, idx -> ordinal to idx }
            assertEquals(shape.getValue("rawUnitCount").jsonPrimitive.int, units.size)
            for (words in listOf(PipelineParams().extractChunkWords, 700)) {
                val ranges = ChunkPlanner.plan(spans, words)
                val expected = shape.getValue("plan$words").jsonArray.map { row ->
                    val indices = row.jsonArray.map { it.jsonPrimitive.int }
                    indices[0]..indices[1]
                }
                assertEquals(name, expected, ranges)
                assertEquals(name, if (words == 350) 18 else 9, ranges.size)
                assertEquals(name, (0 until sentenceCount).toList(), ranges.flatMap { it.toList() })
                assertEquals(name, spans, ranges.flatMap { range ->
                    spans.filter { it.firstIdx >= range.first && it.lastIdx <= range.last }
                })
                assertEquals(name, units, ranges.flatMap { range -> units.filter { it.second in range } })
                assertTrue(name, ranges.zipWithNext().all { (a,b) -> a.last + 1 == b.first })
                for (range in ranges.dropLast(1)) {
                    assertTrue(name, spans.filter { it.firstIdx in range }.sumOf { it.words } >= words)
                }
                if (words == 350) assertTrue(name, spans.filter { it.firstIdx in ranges.last() }.sumOf { it.words } < words)
            }
        }
    }

    @Test fun oversizedMergedSegmentIsKeptWholeAndSmallTailIsNotLost() {
        val spans = listOf(SegmentSpan(0, 2, 420), SegmentSpan(3, 4, 340), SegmentSpan(5, 5, 15), SegmentSpan(6, 6, 5))
        assertEquals(listOf(0..2, 3..5, 6..6), ChunkPlanner.plan(spans, PipelineParams().extractChunkWords))
        assertEquals(0..2 to 3..5, ChunkPlanner.splitHalf(spans, 0..5))
    }
}
