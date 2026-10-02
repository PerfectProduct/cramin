package pro.perfectproduct.cramin.util

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class RetryAfterTest {
    @Test fun deltaDateInvalidAndOverflow() {
        val now = Instant.parse("2026-10-03T12:00:00Z").toEpochMilli()
        assertEquals(12_000L, RetryAfter.milliseconds("12", now))
        assertEquals(12_000L, RetryAfter.milliseconds("Sat, 03 Oct 2026 12:00:12 GMT", now))
        assertEquals(0L, RetryAfter.milliseconds("Sat, 03 Oct 2026 11:00:00 GMT", now))
        assertEquals(60_000L, RetryAfter.milliseconds(Long.MAX_VALUE.toString(), now))
        assertEquals(0L, RetryAfter.milliseconds("-1", now))
        assertNull(RetryAfter.milliseconds("invalid", now))
    }
}
