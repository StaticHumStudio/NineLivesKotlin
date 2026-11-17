package com.ninelivesaudio.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * A server book the server has held progress for: it was in the account's
 * progress, or the phone's listening for it reached the server. Only for
 * such a book does "no progress on the server" mean the progress was
 * removed there, so only such a book takes that reset (#118). Its own table,
 * so the phone's frequent progress writes never touch it. Kept per
 * Audiobookshelf user ([userId], see ProgressAccountScope), so another
 * server or account not holding a book never resets it.
 */
@Entity(tableName = "ServerProgressRecords", primaryKeys = ["UserId", "AudioBookId"])
data class ServerProgressRecordEntity(
    @ColumnInfo(name = "UserId")
    val userId: String,

    @ColumnInfo(name = "AudioBookId")
    val audioBookId: String,
)
