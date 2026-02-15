package com.ninelivesaudio.app.domain.model

import com.ninelivesaudio.app.domain.util.secondsToClockString

data class Bookmark(
    val id: String = "",
    val libraryItemId: String = "",
    val title: String = "",
    val time: Double = 0.0, // seconds
    val createdAt: Long = 0L, // epoch millis
) {
    /** Formatted time for display (HH:MM:SS or MM:SS). */
    val timeFormatted: String
        get() = time.secondsToClockString()
}
