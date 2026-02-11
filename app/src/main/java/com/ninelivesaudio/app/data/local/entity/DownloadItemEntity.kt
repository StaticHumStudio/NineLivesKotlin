package com.ninelivesaudio.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "DownloadItems",
    indices = [Index(value = ["AudioBookId"], name = "idx_downloads_audiobook")]
)
data class DownloadItemEntity(
    @PrimaryKey
    @ColumnInfo(name = "Id")
    val id: String,

    @ColumnInfo(name = "AudioBookId")
    val audioBookId: String,

    @ColumnInfo(name = "Title")
    val title: String,

    @ColumnInfo(name = "Status", defaultValue = "0")
    val status: Int = 0,

    @ColumnInfo(name = "TotalBytes", defaultValue = "0")
    val totalBytes: Long = 0,

    @ColumnInfo(name = "DownloadedBytes", defaultValue = "0")
    val downloadedBytes: Long = 0,

    @ColumnInfo(name = "StartedAt")
    val startedAt: String? = null, // ISO 8601

    @ColumnInfo(name = "CompletedAt")
    val completedAt: String? = null, // ISO 8601

    @ColumnInfo(name = "ErrorMessage")
    val errorMessage: String? = null,

    @ColumnInfo(name = "FilesToDownloadJson")
    val filesToDownloadJson: String? = null,
)
