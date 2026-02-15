package com.ninelivesaudio.app.domain.util

import kotlin.time.Duration

/**
 * Format a Duration as a clock-style string: "HH:MM:SS" or "MM:SS".
 * Negative durations are coerced to zero.
 */
fun Duration.toClockString(): String {
    val totalSeconds = inWholeSeconds.coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        "%02d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

/**
 * Format a Duration as a human-readable string: "Xh Xm", "Xm Xs", or "Xs".
 * Negative durations are coerced to zero.
 */
fun Duration.toHumanReadableString(): String {
    val totalSeconds = inWholeSeconds.coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60

    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${secs}s"
        else -> "${secs}s"
    }
}

/**
 * Format seconds (as Double) into clock-style "HH:MM:SS" or "MM:SS".
 */
fun Double.secondsToClockString(): String {
    val totalSeconds = toLong()
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours >= 1) {
        "%02d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

/**
 * Format a byte count as a human-readable size: "X.X GB", "X.X MB", "X.X KB", or "X B".
 */
fun Long.toDisplaySize(): String = when {
    this >= 1_073_741_824 -> "%.1f GB".format(this / 1_073_741_824.0)
    this >= 1_048_576 -> "%.1f MB".format(this / 1_048_576.0)
    this >= 1024 -> "%.1f KB".format(this / 1024.0)
    else -> "${coerceAtLeast(0)} B"
}
