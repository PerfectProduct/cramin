package pro.perfectproduct.cramin.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleCleanerTest {
    private val vtt = """
        WEBVTT
        Kind: captions
        Language: en

        NOTE this is a comment

        00:00:01.000 --> 00:00:03.500
        <c.colorE5E5E5>The small library</c> stood on the bank

        00:00:03.500 --> 00:00:05.000
        of a slow river.

        00:00:05.000 --> 00:00:06.000
        of a slow river.

        00:00:09.200 --> 00:00:11.000
        <v Narrator>Every morning &amp; every evening</v>

        01:02:03.000 --> 01:02:04.000
        The end.
    """.trimIndent()

    @Test
    fun vttToParagraphsByPausesAndDedup() {
        val cues = SubtitleCleaner.parse(vtt, SubtitleCleaner.Format.VTT)
        assertEquals(5, cues.size)
        assertEquals(1000L, cues[0].startMs)
        assertEquals("The small library stood on the bank", cues[0].text)
        assertEquals(3723000L, cues[4].startMs)
        val text = SubtitleCleaner.toText(vtt)
        assertEquals("The small library stood on the bank of a slow river.\n\nEvery morning & every evening\n\nThe end.", text)
    }

    @Test
    fun srtParses() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nHello there.\n\n2\n00:00:02,100 --> 00:00:03,000\nGeneral <i>Kenobi</i>.\n"
        assertEquals("Hello there. General Kenobi.", SubtitleCleaner.toText(srt, SubtitleCleaner.Format.SRT))
        assertEquals(SubtitleCleaner.Format.SRT, SubtitleCleaner.detect(srt))
    }

    @Test
    fun ttmlParses() {
        val ttml = """
            <?xml version="1.0" encoding="utf-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml"><body><div>
              <p begin="00:00:00.500" end="00:00:02.000">הדייג הזקן<br/>יצא לים</p>
              <p begin="00:00:02.000" end="00:00:03.000"><span>כל בוקר</span> לפני הזריחה.</p>
              <p begin="00:00:06.000" end="00:00:07.000">הסירה שלו הייתה קטנה.</p>
              <p begin="12.5s" end="13s">&lt;b&gt;</p>
            </div></body></tt>
        """.trimIndent()
        assertEquals(SubtitleCleaner.Format.TTML, SubtitleCleaner.detect(ttml))
        val text = SubtitleCleaner.toText(ttml)
        assertEquals("הדייג הזקן יצא לים כל בוקר לפני הזריחה.\n\nהסירה שלו הייתה קטנה.\n\n<b>", text)
        assertEquals(12500L, SubtitleCleaner.ttmlTime("12.5s"))
        assertEquals(3723004L, SubtitleCleaner.ttmlTime("01:02:03.004"))
    }

    @Test
    fun emptyAndGarbage() {
        assertTrue(SubtitleCleaner.toText("WEBVTT\n\n", SubtitleCleaner.Format.VTT).isEmpty())
        assertTrue(SubtitleCleaner.toText("just text without timings", SubtitleCleaner.Format.SRT).isEmpty())
    }
}
