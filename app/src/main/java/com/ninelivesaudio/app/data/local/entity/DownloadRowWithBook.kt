package com.ninelivesaudio.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded

/**
 * A download row joined with the two things the Downloads screen needs from its
 * book: the cover to show and whether the book is already on the device. One
 * query instead of a book lookup per row on every progress tick.
 */
data class DownloadRowWithBook(
    @Embedded
    val download: DownloadItemEntity,

    /** The book's saved cover when it has one, otherwise its server cover. */
    @ColumnInfo(name = "BookCoverPath")
    val coverPath: String?,

    @ColumnInfo(name = "BookIsDownloaded")
    val bookIsDownloaded: Int,
)

/**
 * A server book whose files are on the device, read from the book row itself.
 * The Downloads screen lists these whether or not a download row survived, so
 * offline books never drop out of the one screen built to manage them.
 */
data class DownloadedBookRow(
    @ColumnInfo(name = "Id")
    val id: String,

    @ColumnInfo(name = "Title")
    val title: String,

    /** The book's saved cover when it has one, otherwise its server cover. */
    @ColumnInfo(name = "BookCoverPath")
    val coverPath: String?,
)
