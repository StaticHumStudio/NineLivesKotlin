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

    @Query("SELECT AudioBookId FROM ServerProgressRecords WHERE UserId = :userId AND AudioBookId IN (:ids)")
    suspend fun heldAmong(userId: String, ids: List<String>): List<String>

    /** Which of [ids] another known user's server has held progress for. */
    @Query(
        "SELECT DISTINCT AudioBookId FROM ServerProgressRecords " +
            "WHERE UserId != :userId AND UserId != '' AND AudioBookId IN (:ids)"
    )
    suspend fun heldByOthersAmong(userId: String, ids: List<String>): List<String>

    @Query("DELETE FROM ServerProgressRecords WHERE UserId = :userId AND AudioBookId = :id")
    suspend fun delete(userId: String, id: String)

    /** Records kept before the user was known go to [userId]. */
    @Query("UPDATE OR IGNORE ServerProgressRecords SET UserId = :userId WHERE UserId = ''")
    suspend fun claimUnscoped(userId: String)
}
