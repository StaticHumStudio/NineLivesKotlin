package com.ninelivesaudio.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ninelivesaudio.app.data.local.entity.DownloadItemEntity
import com.ninelivesaudio.app.data.local.entity.DownloadRowWithBook
import com.ninelivesaudio.app.data.local.entity.DownloadedBookRow
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
     * Every download row whose book is still cached, with that book's cover and
     * downloaded flag, for the Downloads screen. Rows for a book that left the
     * database drop out, as they always have. Which rows land in the active and
     * completed lists is decided in Kotlin by `splitDownloadRows`.
     */
    @Query(
        """
        SELECT d.*,
               COALESCE(ab.LocalCoverPath, ab.CoverPath) AS BookCoverPath,
               ab.IsDownloaded AS BookIsDownloaded
        FROM DownloadItems d
        INNER JOIN AudioBooks ab ON ab.Id = d.AudioBookId
        """
    )
    fun observeAllWithBooks(): Flow<List<DownloadRowWithBook>>

    /**
     * Every server book on the device, from the book rows. Download rows can be
     * gone (an older Clear All deleted them and kept the files), and the
     * Downloads screen still has to list the book. Reads only the three columns
     * the screen needs, never the chapter or file blobs.
     */
    @Query(
        """
        SELECT Id, Title, COALESCE(LocalCoverPath, CoverPath) AS BookCoverPath
        FROM AudioBooks
        WHERE IsDownloaded = 1 AND IsLocal = 0
        """
    )
    fun observeDownloadedServerBooks(): Flow<List<DownloadedBookRow>>

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
}
