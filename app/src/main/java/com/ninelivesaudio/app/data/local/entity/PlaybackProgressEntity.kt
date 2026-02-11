package com.ninelivesaudio.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "PlaybackProgress")
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
