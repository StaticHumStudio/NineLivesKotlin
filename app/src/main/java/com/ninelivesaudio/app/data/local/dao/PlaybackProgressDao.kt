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

    /** Review prompts may credit confirmed LOCAL rows plus this exact active remote owner. */
    @Query("""
        SELECT COUNT(*) FROM PlaybackProgress pp
        INNER JOIN AudioBooks ab ON ab.Id = pp.AudioBookId
        WHERE pp.IsFinished = 1 AND (ab.IsLocal = 1 OR
            (:remoteIdPrefix IS NOT NULL AND ab.IsLocal = 0 AND substr(ab.Id, 1, length(:remoteIdPrefix)) = :remoteIdPrefix))
    """)
    suspend fun countFinishedForReview(remoteIdPrefix: String?): Int

    @Query("""
        SELECT COUNT(*) FROM PlaybackProgress pp
        INNER JOIN AudioBooks ab ON ab.Id = pp.AudioBookId
        WHERE pp.PositionSeconds > 60 AND (ab.IsLocal = 1 OR
            (:remoteIdPrefix IS NOT NULL AND ab.IsLocal = 0 AND substr(ab.Id, 1, length(:remoteIdPrefix)) = :remoteIdPrefix))
    """)
    suspend fun countStartedForReview(remoteIdPrefix: String?): Int

    @Query("DELETE FROM PlaybackProgress WHERE AudioBookId = :audioBookId")
    suspend fun deleteByAudioBookId(audioBookId: String)

    @Query("DELETE FROM PlaybackProgress")
    suspend fun deleteAll()
}

data class PositionResult(
    val PositionSeconds: Double,
    val IsFinished: Int,
)
