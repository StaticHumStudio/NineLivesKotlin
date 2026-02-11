package com.ninelivesaudio.app.data.local.entity

import androidx.room.Embedded

/**
 * Result type for the "Nine Lives" recently-played JOIN query.
 * Embeds the full AudioBookEntity and adds the LastPlayedAt timestamp.
 */
data class RecentlyPlayedResult(
    @Embedded
    val audioBook: AudioBookEntity,

    val LastPlayedAt: String?, // from PlaybackProgress.UpdatedAt
)
