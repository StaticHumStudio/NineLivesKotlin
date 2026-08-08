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

    /**
     * Active downloads. Status 0=Queued, 1=Downloading, 2=Paused, 6=Preparing.
     *
     * Preparing is included so a provisional slot claim stays VISIBLE while its
     * metadata fetch is in flight. Leaving it out would make a book the user
     * just asked for vanish from the Downloads screen for the length of a
     * network round trip, which reads as the tap having done nothing.
     */
    @Query("SELECT * FROM DownloadItems WHERE Status IN (0, 1, 2, 6) ORDER BY StartedAt DESC")
    fun observeActive(): Flow<List<DownloadItemEntity>>

    /**
     * Downloadable items for the drain worker: Queued (0) or interrupted
     * Downloading (1). Paused/Completed/Failed/Cancelled are excluded so the
     * worker never auto-resumes a user-paused or finished download.
     *
     * Preparing (6) is excluded on purpose and must stay excluded. It is a slot
     * claim taken BEFORE the metadata fetch returns, so it has no file list yet.
     * Letting the drain pick it up would start a download of nothing.
     */
    @Query("SELECT * FROM DownloadItems WHERE Status IN (0, 1) ORDER BY StartedAt ASC")
    suspend fun getDownloadable(): List<DownloadItemEntity>

    /** Get completed downloads. Status 3=Completed */
    @Query("SELECT * FROM DownloadItems WHERE Status = 3 ORDER BY CompletedAt DESC")
    fun observeCompleted(): Flow<List<DownloadItemEntity>>
}
