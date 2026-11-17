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

    @Query("SELECT AudioBookId FROM ServerProgressRecords WHERE ServerKey = :serverKey AND AudioBookId IN (:ids)")
    suspend fun heldAmong(serverKey: String, ids: List<String>): List<String>

    @Query("DELETE FROM ServerProgressRecords WHERE ServerKey = :serverKey AND AudioBookId = :id")
    suspend fun delete(serverKey: String, id: String)

    /** Records kept before they carried a server go to [serverKey]. */
    @Query("UPDATE OR IGNORE ServerProgressRecords SET ServerKey = :serverKey WHERE ServerKey = ''")
    suspend fun claimUnscoped(serverKey: String)
}
