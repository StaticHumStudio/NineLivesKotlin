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
        ownerKey: String,
        progress: PlaybackProgressEntity,
        pending: PendingProgressEntity,
    ): Long {
        require(pending.ownerKey == ownerKey) {
            "Pending progress must carry the same owner as its live identity"
        }
        deleteForOwnerAndItem(ownerKey, pending.itemId)
        upsertPlaybackProgress(progress)
        return insert(pending)
    }

    @Query("SELECT * FROM PendingProgressUpdates ORDER BY Timestamp ASC")
    suspend fun getAll(): List<PendingProgressEntity>

    @Query("SELECT * FROM PendingProgressUpdates WHERE OwnerKey IS NULL ORDER BY Id ASC")
    suspend fun getLegacyUnowned(): List<PendingProgressEntity>

    @Query("SELECT * FROM PendingProgressUpdates WHERE OwnerKey = :ownerKey AND ItemId = :itemId ORDER BY Id ASC")
    suspend fun getForOwnerAndItem(ownerKey: String, itemId: String): List<PendingProgressEntity>

    @Query("SELECT * FROM PendingProgressUpdates WHERE OwnerKey = :ownerKey ORDER BY Id ASC")
    suspend fun getDeliverableForOwner(ownerKey: String): List<PendingProgressEntity>

    @Query("SELECT COUNT(*) FROM PendingProgressUpdates WHERE OwnerKey = :ownerKey")
    suspend fun countDeliverableForOwner(ownerKey: String): Int

    @Query("DELETE FROM PendingProgressUpdates")
    suspend fun deleteAll()

    @Query("DELETE FROM PendingProgressUpdates WHERE OwnerKey = :ownerKey AND ItemId = :itemId AND Id IN (:ids)")
    suspend fun deleteIdsForOwnerAndItem(ownerKey: String, itemId: String, ids: List<Long>)

    @Query("DELETE FROM PendingProgressUpdates WHERE OwnerKey = :ownerKey AND ItemId = :itemId")
    suspend fun deleteForOwnerAndItem(ownerKey: String, itemId: String)
}
