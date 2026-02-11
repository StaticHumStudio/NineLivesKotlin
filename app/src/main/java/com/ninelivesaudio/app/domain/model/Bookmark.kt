package com.ninelivesaudio.app.domain.model

data class Bookmark(
    val id: String = "",
    val libraryItemId: String = "",
    val title: String = "",
    val time: Double = 0.0, // seconds
    val createdAt: Long = 0L, // epoch millis
) {
    /** Formatted time for display (HH:MM:SS or MM:SS). */
    val timeFormatted: String
        get() {
            val totalSeconds = time.toLong()
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return if (hours >= 1) {
                "%02d:%02d:%02d".format(hours, minutes, seconds)
            } else {
                "%02d:%02d".format(minutes, seconds)
            }
        }
}
