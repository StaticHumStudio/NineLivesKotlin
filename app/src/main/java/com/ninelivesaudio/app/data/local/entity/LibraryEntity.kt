package com.ninelivesaudio.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "Libraries")
data class LibraryEntity(
    @PrimaryKey
    @ColumnInfo(name = "Id")
    val id: String,

    @ColumnInfo(name = "Name")
    val name: String,

    @ColumnInfo(name = "DisplayOrder", defaultValue = "0")
    val displayOrder: Int = 0,

    @ColumnInfo(name = "Icon", defaultValue = "audiobook")
    val icon: String = "audiobook",

    @ColumnInfo(name = "MediaType", defaultValue = "book")
    val mediaType: String = "book",

    @ColumnInfo(name = "FoldersJson")
    val foldersJson: String? = null,
)
