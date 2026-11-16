package com.ninelivesaudio.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ninelivesaudio.app.data.local.entity.ServerProgressRecordEntity

@Dao
interface ServerProgressRecordDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(records: List<ServerProgressRecordEntity>)

    @Query("SELECT AudioBookId FROM ServerProgressRecords WHERE AudioBookId IN (:ids)")
    suspend fun heldAmong(ids: List<String>): List<String>

    @Query("DELETE FROM ServerProgressRecords WHERE AudioBookId = :id")
    suspend fun delete(id: String)
}
