package pro.perfectproduct.cramin.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pro.perfectproduct.cramin.testing.Fixtures
import pro.perfectproduct.cramin.util.Lang

class LangDetectorTest {
    @Test
    fun detectsFixtures() {
        assertEquals(Lang.EN, LangDetector.detect(Fixtures.text(Lang.EN)))
        assertEquals(Lang.RU, LangDetector.detect(Fixtures.text(Lang.RU)))
        assertEquals(Lang.HE, LangDetector.detect(Fixtures.text(Lang.HE)))
    }

    @Test
    fun mixedTextBelowThresholdIsUndetermined() {
        // Половина латиницы, половина кириллицы: ни одна письменность не набирает 60 %.
        assertNull(LangDetector.detect("Hello world friends привет мир друзья"))
        // 70 % латиницы — уже английский.
        assertEquals(Lang.EN, LangDetector.detect("Hello world dear friends of mine привет"))
    }

    @Test
    fun hebrewWithNiqqudAndDigits() {
        assertEquals(Lang.HE, LangDetector.detect("שָׁלוֹם עוֹלָם 2026! הַדַּיָּג יָצָא לַיָּם"))
    }

    @Test
    fun tooFewLettersIsUndetermined() {
        assertNull(LangDetector.detect("12345 !!! ---"))
        assertNull(LangDetector.detect("ok 42"))
        val a = LangDetector.analyze("ok 42")
        assertEquals(2, a.letters)
    }
}
