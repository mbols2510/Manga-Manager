package com.mbols2510.mangamanager.ui.common

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * A server ISO-8601 timestamp (`2026-11-07T10:00:00+00:00`, or without an offset) as a short local date and time in
 * the current locale and time zone; null when it is missing or cannot be read.
 */
fun formatTimestamp(iso: String?, zone: ZoneId = ZoneId.systemDefault()): String? {
    if (iso.isNullOrBlank()) return null
    val local = runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(zone).toLocalDateTime() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(iso) }.getOrNull()
        ?: return null
    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(local)
}
