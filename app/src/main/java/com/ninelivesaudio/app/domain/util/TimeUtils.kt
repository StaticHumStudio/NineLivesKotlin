package com.ninelivesaudio.app.domain.util

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val isoFormatter = DateTimeFormatter.ISO_INSTANT

/**
 * Parse an ISO-8601 timestamp string to epoch milliseconds.
 * Tries ISO_INSTANT first, then ISO_OFFSET_DATE_TIME as fallback.
 * Returns null if parsing fails.
 */
fun String.toEpochMillis(): Long? = try {
    Instant.from(isoFormatter.parse(this)).toEpochMilli()
} catch (_: Exception) {
    try {
        java.time.OffsetDateTime.parse(this).toInstant().toEpochMilli()
    } catch (_: Exception) {
        null
    }
}

/**
 * Format epoch milliseconds as an ISO-8601 offset date-time string.
 */
fun Long.toIso8601(): String =
    Instant.ofEpochMilli(this).atOffset(ZoneOffset.UTC)
        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
