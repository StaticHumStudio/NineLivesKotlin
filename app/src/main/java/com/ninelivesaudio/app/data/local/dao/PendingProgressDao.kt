package com.ninelivesaudio.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.ninelivesaudio.app.data.local.entity.PendingProgressEntity

@Dao
interface PendingProgressDao {

    @Insert
    suspend fun insert(entry: PendingProgressEntity)

    @Query("SELECT * FROM PendingProgressUpdates ORDER BY Timestamp ASC")
    suspend fun getAll(): List<PendingProgressEntity>

    @Query("SELECT COUNT(*) FROM PendingProgressUpdates")
    suspend fun getCount(): Int

    @Query("DELETE FROM PendingProgressUpdates")
    suspend fun deleteAll()

    @Query("DELETE FROM PendingProgressUpdates WHERE Id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}
