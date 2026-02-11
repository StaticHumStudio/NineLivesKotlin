package com.ninelivesaudio.app.domain.model

/**
 * Represents an active playback session from the Audiobookshelf server.
 */
data class PlaybackSessionInfo(
    val id: String = "",
    val itemId: String = "",
    val episodeId: String? = null,
    val currentTime: Double = 0.0,
    val duration: Double = 0.0,
    val mediaType: String = "book",
    val audioTracks: List<AudioStreamInfo> = emptyList(),
    val chapters: List<Chapter> = emptyList(),
)

data class AudioStreamInfo(
    val index: Int = 0,
    val codec: String = "",
    val title: String? = null,
    val duration: Double = 0.0,
    val contentUrl: String = "",
)
