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
        "SELECT * FROM OfflineListeningSessions WHERE ItemId = :itemId AND ServerKey = :serverKey " +
            "ORDER BY UpdatedAt ASC"
    )
    suspend fun getForItemOnServer(itemId: String, serverKey: String): List<OfflineListeningSessionEntity>

    /** Books of [serverKey] with listening time that server does not hold yet. */
    @Query(
        "SELECT DISTINCT ItemId FROM OfflineListeningSessions " +
            "WHERE ServerKey = :serverKey AND TimeListening > SyncedTimeListening"
    )
    suspend fun getItemIdsWithUnsynced(serverKey: String): List<String>

    @Query("SELECT COUNT(*) FROM OfflineListeningSessions WHERE ItemId = :itemId AND TimeListening > SyncedTimeListening")
    suspend fun getUnsyncedCountForItem(itemId: String): Int

    @Query("SELECT COUNT(*) FROM OfflineListeningSessions WHERE TimeListening > SyncedTimeListening")
    suspend fun getUnsyncedCount(): Int

    /** Delivered sessions too old for more listening to join them. */
    @Query(
        "DELETE FROM OfflineListeningSessions " +
            "WHERE ItemId = :itemId AND ServerKey = :serverKey " +
            "AND TimeListening <= SyncedTimeListening AND UpdatedAt < :before"
    )
    suspend fun deleteSyncedBefore(itemId: String, serverKey: String, before: Long)

    @Query("DELETE FROM OfflineListeningSessions WHERE ItemId = :itemId AND ServerKey = :serverKey")
    suspend fun deleteForItemOnServer(itemId: String, serverKey: String)

    /** Sessions saved before they carried a server go to [serverKey]. */
    @Query("UPDATE OfflineListeningSessions SET ServerKey = :serverKey WHERE ServerKey = ''")
    suspend fun claimUnscoped(serverKey: String)

    @Query("DELETE FROM OfflineListeningSessions")
    suspend fun deleteAll()
}
