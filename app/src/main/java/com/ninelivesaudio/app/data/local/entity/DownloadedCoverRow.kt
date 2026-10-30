package com.ninelivesaudio.app.data.local.entity

/** What a saved cover refresh reads about one downloaded server book. */
data class DownloadedCoverRow(
    val id: String,
    val coverPath: String?,
    val localCoverPath: String?,
    val localPath: String?,
)
