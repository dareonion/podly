package com.podly.ui.util

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

fun formatPosition(positionMs: Long): String {
    val totalSeconds = (positionMs / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}
