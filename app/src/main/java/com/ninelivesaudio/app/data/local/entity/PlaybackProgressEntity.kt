package com.ninelivesaudio.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "PlaybackProgress",
    // Lets the recently played queries read newest first and stop at LIMIT.
    indices = [Index(value = ["UpdatedAt"], name = "idx_playback_progress_updated")],
)
data class PlaybackProgressEntity(
    @PrimaryKey
    @ColumnInfo(name = "AudioBookId")
    val audioBookId: String,

    @ColumnInfo(name = "PositionSeconds", defaultValue = "0")
    val positionSeconds: Double = 0.0,

    @ColumnInfo(name = "IsFinished", defaultValue = "0")
    val isFinished: Int = 0, // 0 = false, 1 = true

    @ColumnInfo(name = "UpdatedAt")
    val updatedAt: String? = null, // ISO 8601
)
