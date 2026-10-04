package com.ninelivesaudio.app.data.local.entity

import androidx.room.ColumnInfo

/**
 * The local-only fields a server sync must keep on a cached book: download
 * state, on-disk paths, local progress, and the archive flag. Read instead of
 * the whole row so a big library's sync does not load every book's audio
 * file and chapter lists into memory just to keep a handful of columns.
 */
data class SyncMergeState(
    @ColumnInfo(name = "Id") val id: String,
    @ColumnInfo(name = "IsDownloaded") val isDownloaded: Int = 0,
    @ColumnInfo(name = "LocalPath") val localPath: String? = null,
    @ColumnInfo(name = "LocalCoverPath") val localCoverPath: String? = null,
    @ColumnInfo(name = "CurrentTimeSeconds") val currentTimeSeconds: Double = 0.0,
    @ColumnInfo(name = "Progress") val progress: Double = 0.0,
    @ColumnInfo(name = "IsFinished") val isFinished: Int = 0,
    @ColumnInfo(name = "ArchivedAt") val archivedAt: Long? = null,
)
