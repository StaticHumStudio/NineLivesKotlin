package com.ninelivesaudio.app.service.local

import kotlin.time.Duration

/**
 * Intermediate representation of a local book discovered during folder scan.
 * Mapped to [AudioBook] via [toAudioBook] before Room import.
 */
data class ScannedLocalBook(
    val id: String,
    val title: String,
    val author: String,
    val narrator: String? = null,
    val description: String? = null,
    val coverUri: String? = null,
    val duration: Duration = Duration.ZERO,
    val tracks: List<ScannedTrack>,
)

data class ScannedTrack(
    val id: String,
    val uri: String,
    val filename: String,
    val index: Int,
    val duration: Duration = Duration.ZERO,
    val mimeType: String? = null,
    val size: Long = 0,
)
