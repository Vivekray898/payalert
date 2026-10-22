package com.vivekray898.payalert.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatAmount(value: Double): String = String.format(Locale.US, "%,.2f", value)

fun formatTimestamp(millis: Long): String =
    SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(millis))

/**
 * A short "how long ago" phrase, used for relay timings.
 *
 * The relay is a background path, so an age is more useful than a wall-clock time: it answers
 * "was that a second ago or ten minutes ago" at a glance.
 */
fun formatAge(millis: Long, now: Long = System.currentTimeMillis()): String {
    val seconds = ((now - millis) / 1000L).coerceAtLeast(0L)
    return when {
        seconds < 60L -> "${seconds}s ago"
        seconds < 3600L -> "${seconds / 60L}m ago"
        seconds < 86_400L -> "${seconds / 3600L}h ago"
        else -> "${seconds / 86_400L}d ago"
    }
}
