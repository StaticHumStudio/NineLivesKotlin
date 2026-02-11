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

    @Query("DELETE FROM PlaybackProgress WHERE AudioBookId = :audioBookId")
    suspend fun deleteByAudioBookId(audioBookId: String)

    @Query("DELETE FROM PlaybackProgress")
    suspend fun deleteAll()
}

data class PositionResult(
    val PositionSeconds: Double,
    val IsFinished: Int,
)
