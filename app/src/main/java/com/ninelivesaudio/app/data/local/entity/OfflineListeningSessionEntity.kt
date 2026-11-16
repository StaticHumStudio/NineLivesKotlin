package com.ninelivesaudio.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Listening of a server book made while no server session was open (a
 * download played offline, or a server session that could not be opened).
 * It reaches the server through Audiobookshelf's offline session sync under
 * [id], so a later delivery of the same session replaces the earlier one
 * instead of counting its time twice.
 *
 * Times are epoch milliseconds, positions and lengths seconds.
 * [timeListening] is the session's whole listening time and
 * [syncedTimeListening] the part the server already holds.
 */
@Entity(
    tableName = "OfflineListeningSessions",
    indices = [Index(value = ["ItemId"], name = "idx_offline_session_item")],
)
data class OfflineListeningSessionEntity(
    @PrimaryKey
    @ColumnInfo(name = "Id")
    val id: String,

    @ColumnInfo(name = "ItemId")
    val itemId: String,

    @ColumnInfo(name = "DisplayTitle")
    val displayTitle: String? = null,

    @ColumnInfo(name = "StartedAt")
    val startedAt: Long,

    @ColumnInfo(name = "UpdatedAt")
    val updatedAt: Long,

    @ColumnInfo(name = "StartTime", defaultValue = "0")
    val startTime: Double = 0.0,

    @ColumnInfo(name = "CurrentTime", defaultValue = "0")
    val currentTime: Double = 0.0,

    @ColumnInfo(name = "Duration", defaultValue = "0")
    val duration: Double = 0.0,

    @ColumnInfo(name = "TimeListening", defaultValue = "0")
    val timeListening: Double = 0.0,

    @ColumnInfo(name = "SyncedTimeListening", defaultValue = "0")
    val syncedTimeListening: Double = 0.0,
)
