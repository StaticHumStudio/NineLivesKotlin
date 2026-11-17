package com.ninelivesaudio.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.ninelivesaudio.app.data.local.entity.PendingProgressEntity
import com.ninelivesaudio.app.data.local.entity.PlaybackProgressEntity

@Dao
interface PendingProgressDao {

    @Insert
    suspend fun insert(entry: PendingProgressEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaybackProgress(progress: PlaybackProgressEntity)

    @Transaction
    suspend fun saveProgressAndEnqueue(
        progress: PlaybackProgressEntity,
        pending: PendingProgressEntity,
    ): Long {
        deleteByItemId(pending.itemId)
        upsertPlaybackProgress(progress)
        return insert(pending)
    }

    @Query("SELECT * FROM PendingProgressUpdates ORDER BY Timestamp ASC")
    suspend fun getAll(): List<PendingProgressEntity>

    @Query("SELECT COUNT(*) FROM PendingProgressUpdates")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM PendingProgressUpdates WHERE ItemId = :itemId")
    suspend fun getCountForItem(itemId: String): Int

    @Query("SELECT * FROM PendingProgressUpdates WHERE ItemId = :itemId ORDER BY Timestamp ASC")
    suspend fun getForItem(itemId: String): List<PendingProgressEntity>

    @Query(
        "SELECT * FROM PendingProgressUpdates WHERE ItemId = :itemId AND ServerKey = :serverKey " +
            "ORDER BY Timestamp ASC"
    )
    suspend fun getForItemOnServer(itemId: String, serverKey: String): List<PendingProgressEntity>

    /** Books with spots queued for [serverKey]. */
    @Query("SELECT DISTINCT ItemId FROM PendingProgressUpdates WHERE ServerKey = :serverKey")
    suspend fun getItemIdsOnServer(serverKey: String): List<String>

    @Query("DELETE FROM PendingProgressUpdates WHERE ItemId = :itemId AND ServerKey = :serverKey")
    suspend fun deleteByItemIdOnServer(itemId: String, serverKey: String)

    /** Rows queued before rows carried a server (2.1.x) go to [serverKey]. */
    @Query("UPDATE PendingProgressUpdates SET ServerKey = :serverKey WHERE ServerKey = ''")
    suspend fun claimUnscoped(serverKey: String)

    @Query("DELETE FROM PendingProgressUpdates")
    suspend fun deleteAll()

    @Query("DELETE FROM PendingProgressUpdates WHERE Id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM PendingProgressUpdates WHERE ItemId = :itemId")
    suspend fun deleteByItemId(itemId: String)
}
