package com.ninelivesaudio.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ninelivesaudio.app.data.local.entity.LibraryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {

    @Query("SELECT * FROM Libraries ORDER BY DisplayOrder")
    fun observeAll(): Flow<List<LibraryEntity>>

    @Query("SELECT * FROM Libraries WHERE IsLocal = 0 ORDER BY DisplayOrder")
    fun observeAudiobookshelf(): Flow<List<LibraryEntity>>

    @Query("SELECT * FROM Libraries WHERE IsLocal = 1 ORDER BY DisplayOrder")
    fun observeLocal(): Flow<List<LibraryEntity>>

    @Query("SELECT * FROM Libraries ORDER BY DisplayOrder")
    suspend fun getAll(): List<LibraryEntity>

    @Query("SELECT * FROM Libraries WHERE IsLocal = 0 ORDER BY DisplayOrder")
    suspend fun getAudiobookshelf(): List<LibraryEntity>

    @Query("SELECT * FROM Libraries WHERE IsLocal = 1 ORDER BY DisplayOrder")
    suspend fun getLocal(): List<LibraryEntity>

    @Query("SELECT * FROM Libraries WHERE Id = :id")
    suspend fun getById(id: String): LibraryEntity?

    @Query("SELECT * FROM Libraries WHERE FolderUri = :folderUri AND IsLocal = 1 LIMIT 1")
    suspend fun getLocalByFolderUri(folderUri: String): LibraryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(library: LibraryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(libraries: List<LibraryEntity>)

    @Query("DELETE FROM Libraries WHERE Id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM Libraries WHERE Id = :id AND IsLocal = 1")
    suspend fun deleteLocalById(id: String)

    /**
     * Prune cached SERVER libraries a complete /libraries fetch no longer
     * reports (issue #14, PR #30 review, finding A). This list contains
     * account libraries, not the potentially much larger book catalog.
     */
    @Query("DELETE FROM Libraries WHERE IsLocal = 0 AND Id NOT IN (:ids)")
    suspend fun deleteMissingAudiobookshelf(ids: List<String>)

    /** Library count for the bug report's stored-state line. */
    @Query("SELECT COUNT(*) FROM Libraries")
    suspend fun countAll(): Int

    @Query("DELETE FROM Libraries")
    suspend fun deleteAll()

    @Query("DELETE FROM Libraries WHERE IsLocal = 0")
    suspend fun deleteAudiobookshelf()
}
