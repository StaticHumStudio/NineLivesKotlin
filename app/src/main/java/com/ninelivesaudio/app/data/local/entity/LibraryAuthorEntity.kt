package com.ninelivesaudio.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * One Audiobookshelf author in one server library, from
 * /api/libraries/{id}/authors (issue #87). The library list only sends a
 * book's authors joined into one line ("Smith, John, Neil Gaiman"), so Author
 * view matches that line against these names to group by the real author.
 * A library's rows are replaced whole on each good fetch and kept as they are
 * when a fetch fails.
 */
@Entity(tableName = "LibraryAuthors", primaryKeys = ["LibraryId", "AuthorId"])
data class LibraryAuthorEntity(
    @ColumnInfo(name = "LibraryId")
    val libraryId: String,

    @ColumnInfo(name = "AuthorId")
    val authorId: String,

    @ColumnInfo(name = "Name")
    val name: String,
)
