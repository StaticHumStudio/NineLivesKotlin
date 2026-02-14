package com.ninelivesaudio.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded

/**
 * Result type for the "Nine Lives" recently-played JOIN query.
 * Embeds the full AudioBookEntity and adds the last-played timestamp.
 */
data class RecentlyPlayedResult(
    @Embedded
    val audioBook: AudioBookEntity,

    @ColumnInfo(name = "lastPlayedAt")
    val lastPlayedAt: String?, // from PlaybackProgress.UpdatedAt
)
