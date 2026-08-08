package com.ninelivesaudio.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ninelivesaudio.app.data.local.entity.PlaybackProgressEntity

@Dao
interface PlaybackProgressDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: PlaybackProgressEntity)

    @Query("SELECT * FROM PlaybackProgress WHERE AudioBookId = :audioBookId")
    suspend fun getByAudioBookId(audioBookId: String): PlaybackProgressEntity?

    @Query("SELECT PositionSeconds, IsFinished FROM PlaybackProgress WHERE AudioBookId = :audioBookId")
    suspend fun getPositionAndFinished(audioBookId: String): PositionResult?

    /** Books finished end to end. Used by the In-App Review eligibility gate. */
    @Query("SELECT COUNT(*) FROM PlaybackProgress WHERE IsFinished = 1")
    suspend fun countFinished(): Int

    /**
     * Books with a real position recorded, used as the softer "real use" signal
     * for anyone still working through a forty-hour book.
     */
    @Query("SELECT COUNT(*) FROM PlaybackProgress WHERE PositionSeconds > 60")
    suspend fun countStarted(): Int

    @Query("DELETE FROM PlaybackProgress WHERE AudioBookId = :audioBookId")
    suspend fun deleteByAudioBookId(audioBookId: String)

    @Query("DELETE FROM PlaybackProgress")
    suspend fun deleteAll()
}

data class PositionResult(
    val PositionSeconds: Double,
    val IsFinished: Int,
)
