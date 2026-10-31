package com.ninelivesaudio.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.ninelivesaudio.app.data.local.entity.LocalListeningSessionEntity

@Dao
interface LocalListeningSessionDao {

    @Insert
    suspend fun insert(entry: LocalListeningSessionEntity): Long

    @Query(
        """UPDATE LocalListeningSessions
           SET TimeListening = :timeListening,
               CurrentTime = :currentTime,
               UpdatedAt = :updatedAt
           WHERE Id = :id"""
    )
    suspend fun updateProgress(
        id: Long,
        timeListening: Double,
        currentTime: Double,
        updatedAt: Long,
    )

    @Query("SELECT * FROM LocalListeningSessions ORDER BY StartedAt DESC")
    suspend fun getAll(): List<LocalListeningSessionEntity>

    @Query(
        """SELECT * FROM LocalListeningSessions
           WHERE AudioBookId = :audioBookId
           ORDER BY StartedAt DESC"""
    )
    suspend fun getByAudioBookId(audioBookId: String): List<LocalListeningSessionEntity>

    @Query(
        """SELECT * FROM LocalListeningSessions
           WHERE LibraryId = :libraryId
           ORDER BY StartedAt DESC"""
    )
    suspend fun getByLibrary(libraryId: String): List<LocalListeningSessionEntity>

    @Query("DELETE FROM LocalListeningSessions WHERE Id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM LocalListeningSessions WHERE AudioBookId = :audioBookId")
    suspend fun deleteByAudioBookId(audioBookId: String)

    /** Re-point every session of [from] at [to] (a moved or set aside book). */
    @Query("UPDATE LocalListeningSessions SET AudioBookId = :to WHERE AudioBookId = :from")
    suspend fun moveToBook(from: String, to: String): Int

    @Query("DELETE FROM LocalListeningSessions")
    suspend fun deleteAll()
}
