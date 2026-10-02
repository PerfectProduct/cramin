package pro.perfectproduct.cramin.util

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object RetryAfter {
    const val MAX_MS = 60_000L
    fun milliseconds(header: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
        val value = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        value.toLongOrNull()?.let { return it.coerceIn(0, MAX_MS / 1000) * 1000 }
        return runCatching {
            (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMillis)
                .coerceIn(0, MAX_MS)
        }.getOrNull()
    }
}
