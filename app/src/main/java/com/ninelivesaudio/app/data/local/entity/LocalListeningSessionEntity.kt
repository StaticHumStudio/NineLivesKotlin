package com.ninelivesaudio.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per local-mode listening session. Mirrors the shape of the
 * Audiobookshelf server's ListeningSession so the Nightwatch Dossier can
 * aggregate both sources through the same domain model.
 *
 * StartedAt/UpdatedAt are epoch milliseconds in UTC.
 * TimeListening is accumulated playback seconds for the session (paused time excluded).
 */
@Entity(
    tableName = "LocalListeningSessions",
    indices = [
        Index(value = ["AudioBookId"], name = "idx_local_session_book"),
        Index(value = ["LibraryId"], name = "idx_local_session_library"),
        Index(value = ["StartedAt"], name = "idx_local_session_started"),
    ],
)
data class LocalListeningSessionEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "Id")
    val id: Long = 0,

    @ColumnInfo(name = "AudioBookId")
    val audioBookId: String,

    @ColumnInfo(name = "LibraryId")
    val libraryId: String,

    @ColumnInfo(name = "StartedAt")
    val startedAt: Long,

    @ColumnInfo(name = "UpdatedAt")
    val updatedAt: Long,

    @ColumnInfo(name = "TimeListening", defaultValue = "0")
    val timeListening: Double = 0.0,

    @ColumnInfo(name = "CurrentTime", defaultValue = "0")
    val currentTime: Double = 0.0,

    @ColumnInfo(name = "DisplayTitle")
    val displayTitle: String? = null,
)
