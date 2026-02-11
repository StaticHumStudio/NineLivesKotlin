package com.ninelivesaudio.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "PendingProgressUpdates",
    indices = [Index(value = ["ItemId"], name = "idx_pending_item")]
)
data class PendingProgressEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "Id")
    val id: Long = 0,

    @ColumnInfo(name = "ItemId")
    val itemId: String,

    @ColumnInfo(name = "CurrentTime", defaultValue = "0")
    val currentTime: Double = 0.0,

    @ColumnInfo(name = "IsFinished", defaultValue = "0")
    val isFinished: Int = 0, // 0 = false, 1 = true

    @ColumnInfo(name = "Timestamp")
    val timestamp: String, // ISO 8601
)
