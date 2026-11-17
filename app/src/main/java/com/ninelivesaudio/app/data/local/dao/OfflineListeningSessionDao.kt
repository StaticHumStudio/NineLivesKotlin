package com.ninelivesaudio.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ninelivesaudio.app.data.local.entity.OfflineListeningSessionEntity

@Dao
interface OfflineListeningSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: OfflineListeningSessionEntity)

    @Query("SELECT * FROM OfflineListeningSessions WHERE ItemId = :itemId ORDER BY UpdatedAt ASC")
    suspend fun getForItem(itemId: String): List<OfflineListeningSessionEntity>

    @Query(
        "SELECT * FROM OfflineListeningSessions WHERE ItemId = :itemId AND UserId = :userId " +
            "ORDER BY UpdatedAt ASC"
    )
    suspend fun getForItemForUser(itemId: String, userId: String): List<OfflineListeningSessionEntity>

    /** Books of [userId] with listening time the server does not hold yet. */
    @Query(
        "SELECT DISTINCT ItemId FROM OfflineListeningSessions " +
            "WHERE UserId = :userId AND TimeListening > SyncedTimeListening"
    )
    suspend fun getItemIdsWithUnsynced(userId: String): List<String>

    @Query("SELECT COUNT(*) FROM OfflineListeningSessions WHERE ItemId = :itemId AND TimeListening > SyncedTimeListening")
    suspend fun getUnsyncedCountForItem(itemId: String): Int

    @Query("SELECT COUNT(*) FROM OfflineListeningSessions WHERE TimeListening > SyncedTimeListening")
    suspend fun getUnsyncedCount(): Int

    /** Delivered sessions too old for more listening to join them. */
    @Query(
        "DELETE FROM OfflineListeningSessions " +
            "WHERE ItemId = :itemId AND UserId = :userId " +
            "AND TimeListening <= SyncedTimeListening AND UpdatedAt < :before"
    )
    suspend fun deleteSyncedBefore(itemId: String, userId: String, before: Long)

    @Query("DELETE FROM OfflineListeningSessions WHERE ItemId = :itemId AND UserId = :userId")
    suspend fun deleteForItemForUser(itemId: String, userId: String)

    /** Sessions saved before the user was known go to [userId]. */
    @Query("UPDATE OfflineListeningSessions SET UserId = :userId WHERE UserId = ''")
    suspend fun claimUnscoped(userId: String)

    @Query("DELETE FROM OfflineListeningSessions")
    suspend fun deleteAll()
}
