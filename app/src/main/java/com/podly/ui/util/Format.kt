package com.podly.ui.util

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

/**
 * "Aug 20, 2026 · 2 weeks ago · 47m" — the line under a recommendation.
 *
 * The absolute date is what Darren asked to see; the relative age is what makes
 * a list of picks scannable for "these are all ancient", which reading a column
 * of formatted dates does not. An episode with no usable date keeps its duration
 * rather than rendering a 1970 one.
 */
fun publishedText(
    pubDateMs: Long,
    durationMs: Long? = null,
    nowMs: Long = System.currentTimeMillis(),
): String? {
    val date = formatDate(pubDateMs)
    val parts = listOfNotNull(
        date,
        date?.let { relativeAge(pubDateMs, nowMs) },
        formatDuration(durationMs),
    )
    return parts.joinToString(" · ").ifBlank { null }
}

fun formatDateTime(epochMs: Long): String? {
    if (epochMs <= 0) return null
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))
}

/** "generated Jul 13, 2026 (2 days ago)", or null if the time is unknown. */
fun generatedText(generatedAtMs: Long, nowMs: Long = System.currentTimeMillis()): String? {
    if (generatedAtMs <= 0L) return null
    return "generated ${formatDate(generatedAtMs)} (${relativeAge(generatedAtMs, nowMs)})"
}

/**
 * "6 weeks ago" for any age.
 *
 * Not `DateUtils.getRelativeTimeSpanString`: past its largest supported unit
 * that returns an absolute date, so stale picks rendered as the stutter
 * "generated Jul 26, 2026 (Jul 26, 2026)" instead of saying how old they are.
 */
internal fun relativeAge(thenMs: Long, nowMs: Long): String {
    val minutes = (nowMs - thenMs) / 60_000
    if (minutes < 1) return "just now"
    if (minutes < 60) return ago(minutes, "minute")
    val hours = minutes / 60
    if (hours < 24) return ago(hours, "hour")
    val days = hours / 24
    if (days < 7) return ago(days, "day")
    if (days < 30) return ago(days / 7, "week")
    val months = days / 30
    if (months < 12) return ago(months, "month")
    return ago(maxOf(1, days / 365), "year")
}

private fun ago(count: Long, unit: String) = "$count $unit${if (count == 1L) "" else "s"} ago"

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
