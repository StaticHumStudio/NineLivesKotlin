package com.ninelivesaudio.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "AudioBooks",
    indices = [Index(value = ["LibraryId"], name = "idx_audiobooks_library")]
)
data class AudioBookEntity(
    @PrimaryKey
    @ColumnInfo(name = "Id")
    val id: String,

    @ColumnInfo(name = "LibraryId")
    val libraryId: String? = null,

    @ColumnInfo(name = "Title")
    val title: String,

    @ColumnInfo(name = "Author")
    val author: String? = null,

    @ColumnInfo(name = "Narrator")
    val narrator: String? = null,

    @ColumnInfo(name = "Description")
    val description: String? = null,

    @ColumnInfo(name = "CoverPath")
    val coverPath: String? = null,

    @ColumnInfo(name = "DurationSeconds", defaultValue = "0")
    val durationSeconds: Double = 0.0,

    @ColumnInfo(name = "AddedAt")
    val addedAt: String? = null, // ISO 8601

    @ColumnInfo(name = "AudioFilesJson")
    val audioFilesJson: String? = null,

    @ColumnInfo(name = "CurrentTimeSeconds", defaultValue = "0")
    val currentTimeSeconds: Double = 0.0,

    @ColumnInfo(name = "Progress", defaultValue = "0")
    val progress: Double = 0.0,

    @ColumnInfo(name = "IsFinished", defaultValue = "0")
    val isFinished: Int = 0, // 0 = false, 1 = true

    @ColumnInfo(name = "IsDownloaded", defaultValue = "0")
    val isDownloaded: Int = 0, // 0 = false, 1 = true

    @ColumnInfo(name = "LocalPath")
    val localPath: String? = null,

    @ColumnInfo(name = "SeriesName")
    val seriesName: String? = null,

    @ColumnInfo(name = "SeriesSequence")
    val seriesSequence: String? = null,

    @ColumnInfo(name = "GenresJson")
    val genresJson: String? = null,

    @ColumnInfo(name = "TagsJson")
    val tagsJson: String? = null,

    @ColumnInfo(name = "ChaptersJson")
    val chaptersJson: String? = null,
)
