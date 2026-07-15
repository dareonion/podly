package com.podly.ui.util

import android.text.format.DateUtils
import androidx.core.text.HtmlCompat
import java.text.DateFormat
import java.util.Date

fun formatDuration(durationMs: Long?): String? {
    if (durationMs == null || durationMs <= 0) return null
    val totalMinutes = durationMs / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

fun formatDate(epochMs: Long): String? {
    if (epochMs <= 0) return null
    return DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMs))
}

fun formatDateTime(epochMs: Long): String? {
    if (epochMs <= 0) return null
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))
}

/** "generated Jul 13, 2026 (2 days ago)", or null if the time is unknown. */
fun generatedText(generatedAtMs: Long): String? {
    if (generatedAtMs <= 0L) return null
    val relative = DateUtils.getRelativeTimeSpanString(
        generatedAtMs,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    )
    return "generated ${formatDate(generatedAtMs)} ($relative)"
}

fun formatPosition(positionMs: Long): String {
    val totalSeconds = (positionMs / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}

fun plainDescription(description: String?): String? {
    if (description.isNullOrBlank()) return null
    return HtmlCompat.fromHtml(description, HtmlCompat.FROM_HTML_MODE_LEGACY)
        .toString()
        .replace("\u00A0", " ")
        .lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n\n")
        .ifBlank { null }
}
