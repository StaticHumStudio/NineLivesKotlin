package com.ninelivesaudio.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per local-mode bookmark. Mirrors the shape of the Audiobookshelf
 * server's Bookmark so the Player can render both sources through the same
 * domain model.
 *
 * AudioBookId + Time is the natural key (matches the server's
 * DELETE /api/me/item/{itemId}/bookmark/{time} contract). A unique index on
 * the pair prevents duplicate bookmarks at the same second.
 *
 * CreatedAt is epoch milliseconds in UTC. Time is the playhead position in
 * seconds at which the bookmark was placed.
 */
@Entity(
    tableName = "LocalBookmarks",
    indices = [
        Index(value = ["AudioBookId"], name = "idx_local_bookmark_book"),
        Index(
            value = ["AudioBookId", "Time"],
            unique = true,
            name = "idx_local_bookmark_book_time",
        ),
    ],
)
data class LocalBookmarkEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "Id")
    val id: Long = 0,

    @ColumnInfo(name = "AudioBookId")
    val audioBookId: String,

    @ColumnInfo(name = "Title")
    val title: String,

    @ColumnInfo(name = "Time")
    val time: Double,

    @ColumnInfo(name = "CreatedAt")
    val createdAt: Long,
)
