package com.ninelivesaudio.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ninelivesaudio.app.data.local.entity.DownloadItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadItemDao {

    @Query("SELECT * FROM DownloadItems ORDER BY StartedAt DESC")
    fun observeAll(): Flow<List<DownloadItemEntity>>

    @Query("SELECT * FROM DownloadItems ORDER BY StartedAt DESC")
    suspend fun getAll(): List<DownloadItemEntity>

    @Query("SELECT * FROM DownloadItems WHERE Id = :id")
    suspend fun getById(id: String): DownloadItemEntity?

    @Query("SELECT * FROM DownloadItems WHERE AudioBookId = :audioBookId")
    suspend fun getByAudioBookId(audioBookId: String): DownloadItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(downloadItem: DownloadItemEntity)

    @Query("DELETE FROM DownloadItems WHERE Id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM DownloadItems")
    suspend fun deleteAll()

    /** Get active downloads (queued, downloading, paused). Status 0=Queued, 1=Downloading, 2=Paused */
    @Query("SELECT * FROM DownloadItems WHERE Status IN (0, 1, 2) ORDER BY StartedAt DESC")
    fun observeActive(): Flow<List<DownloadItemEntity>>

    /** Get completed downloads. Status 3=Completed */
    @Query("SELECT * FROM DownloadItems WHERE Status = 3 ORDER BY CompletedAt DESC")
    fun observeCompleted(): Flow<List<DownloadItemEntity>>
}
