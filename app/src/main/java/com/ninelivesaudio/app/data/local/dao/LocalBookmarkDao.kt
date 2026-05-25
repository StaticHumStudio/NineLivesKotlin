package com.ninelivesaudio.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ninelivesaudio.app.data.local.entity.LocalBookmarkEntity

@Dao
interface LocalBookmarkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: LocalBookmarkEntity): Long

    @Query(
        """SELECT * FROM LocalBookmarks
           WHERE AudioBookId = :audioBookId
           ORDER BY Time ASC"""
    )
    suspend fun getByAudioBookId(audioBookId: String): List<LocalBookmarkEntity>

    @Query(
        """DELETE FROM LocalBookmarks
           WHERE AudioBookId = :audioBookId AND Time = :time"""
    )
    suspend fun deleteByBookAndTime(audioBookId: String, time: Double): Int

    @Query("DELETE FROM LocalBookmarks WHERE AudioBookId = :audioBookId")
    suspend fun deleteAllForBook(audioBookId: String)

    @Query("DELETE FROM LocalBookmarks")
    suspend fun deleteAll()
}
