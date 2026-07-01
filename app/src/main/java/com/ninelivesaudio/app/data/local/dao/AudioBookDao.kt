package com.ninelivesaudio.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.ninelivesaudio.app.data.local.entity.AudioBookEntity
import com.ninelivesaudio.app.data.local.entity.PlaybackProgressEntity
import com.ninelivesaudio.app.data.local.entity.RecentlyPlayedResult
import kotlinx.coroutines.flow.Flow

@Dao
interface AudioBookDao {

    @Query("SELECT * FROM AudioBooks ORDER BY Title")
    fun observeAll(): Flow<List<AudioBookEntity>>

    @Query("SELECT * FROM AudioBooks ORDER BY Title")
    suspend fun getAll(): List<AudioBookEntity>

    @Query("SELECT * FROM AudioBooks WHERE LibraryId = :libraryId ORDER BY Title")
    fun observeByLibrary(libraryId: String): Flow<List<AudioBookEntity>>

    @Query("SELECT * FROM AudioBooks WHERE IsLocal = :isLocal ORDER BY Title")
    fun observeBySource(isLocal: Int): Flow<List<AudioBookEntity>>

    @Query("SELECT * FROM AudioBooks WHERE LibraryId = :libraryId ORDER BY Title")
    suspend fun getByLibrary(libraryId: String): List<AudioBookEntity>

    @Query("SELECT * FROM AudioBooks WHERE IsLocal = :isLocal ORDER BY Title")
    suspend fun getBySource(isLocal: Int): List<AudioBookEntity>

    @Query("SELECT * FROM AudioBooks WHERE Id = :id")
    suspend fun getById(id: String): AudioBookEntity?

    /** Batch lookup by IDs — used by syncLibraryItems to preserve download state. */
    @Query("SELECT * FROM AudioBooks WHERE Id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<AudioBookEntity>

    @Query("SELECT * FROM AudioBooks WHERE Id = :id")
    fun observeById(id: String): Flow<AudioBookEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(audioBook: AudioBookEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(audioBooks: List<AudioBookEntity>)

    @Query("DELETE FROM AudioBooks WHERE Id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM AudioBooks WHERE LibraryId = :libraryId")
    suspend fun deleteByLibrary(libraryId: String)

    @Query("DELETE FROM AudioBooks WHERE LibraryId = :libraryId AND IsLocal = 1")
    suspend fun deleteLocalByLibrary(libraryId: String)

    @Query("DELETE FROM AudioBooks WHERE LibraryId = :libraryId AND IsLocal = 1 AND Id NOT IN (:scannedIds)")
    suspend fun deleteMissingLocalBooks(libraryId: String, scannedIds: List<String>)

    /** Ids of all LOCAL books in a library (live or archived). */
    @Query("SELECT Id FROM AudioBooks WHERE LibraryId = :libraryId AND IsLocal = 1")
    suspend fun getLocalIdsByLibrary(libraryId: String): List<String>

    /** Ids of the archived (soft-deleted) LOCAL books in a library. */
    @Query("SELECT Id FROM AudioBooks WHERE LibraryId = :libraryId AND IsLocal = 1 AND ArchivedAt IS NOT NULL")
    suspend fun getArchivedLocalIdsByLibrary(libraryId: String): List<String>

    /** Soft-delete: stamp ArchivedAt on the given books (skips already-archived). */
    @Query("UPDATE AudioBooks SET ArchivedAt = :archivedAt WHERE Id IN (:ids) AND ArchivedAt IS NULL")
    suspend fun archiveByIds(ids: List<String>, archivedAt: Long)

    @Query("DELETE FROM AudioBooks")
    suspend fun deleteAll()

    @Query("DELETE FROM AudioBooks WHERE IsLocal = 0")
    suspend fun deleteAudiobookshelf()

    /** Nine Lives — recently played books with their last-played timestamp. */
    @Query("""
        SELECT ab.*, pp.UpdatedAt AS lastPlayedAt
        FROM AudioBooks ab
        INNER JOIN PlaybackProgress pp ON ab.Id = pp.AudioBookId
        ORDER BY pp.UpdatedAt DESC
        LIMIT :limit
    """)
    suspend fun getRecentlyPlayed(limit: Int = 9): List<RecentlyPlayedResult>

    /** Nine Lives — observable version for reactive UI. */
    @Query("""
        SELECT ab.*, pp.UpdatedAt AS lastPlayedAt
        FROM AudioBooks ab
        INNER JOIN PlaybackProgress pp ON ab.Id = pp.AudioBookId
        ORDER BY pp.UpdatedAt DESC
        LIMIT :limit
    """)
    fun observeRecentlyPlayed(limit: Int = 9): Flow<List<RecentlyPlayedResult>>

    /** Nine Lives — recently played books filtered by library. */
    @Query("""
        SELECT ab.*, pp.UpdatedAt AS lastPlayedAt
        FROM AudioBooks ab
        INNER JOIN PlaybackProgress pp ON ab.Id = pp.AudioBookId
        WHERE ab.LibraryId = :libraryId
        ORDER BY pp.UpdatedAt DESC
        LIMIT :limit
    """)
    suspend fun getRecentlyPlayedByLibrary(libraryId: String, limit: Int = 9): List<RecentlyPlayedResult>

    /** Nine Lives — observable recently played books filtered by library. */
    @Query("""
        SELECT ab.*, pp.UpdatedAt AS lastPlayedAt
        FROM AudioBooks ab
        INNER JOIN PlaybackProgress pp ON ab.Id = pp.AudioBookId
        WHERE ab.LibraryId = :libraryId
        ORDER BY pp.UpdatedAt DESC
        LIMIT :limit
    """)
    fun observeRecentlyPlayedByLibrary(libraryId: String, limit: Int = 9): Flow<List<RecentlyPlayedResult>>

    /** Get all audiobooks for a library with their last-played timestamp. */
    @Query("""
        SELECT ab.*, pp.UpdatedAt AS lastPlayedAt
        FROM AudioBooks ab
        LEFT JOIN PlaybackProgress pp ON ab.Id = pp.AudioBookId
        WHERE ab.LibraryId = :libraryId
        ORDER BY ab.Title
    """)
    suspend fun getByLibraryWithLastPlayed(libraryId: String): List<RecentlyPlayedResult>

    /** Search audiobooks by title or author. */
    @Query("""
        SELECT * FROM AudioBooks
        WHERE Title LIKE '%' || :query || '%'
           OR Author LIKE '%' || :query || '%'
        ORDER BY Title
    """)
    suspend fun search(query: String): List<AudioBookEntity>

    /** Update just the progress fields on an audiobook. */
    @Query("UPDATE AudioBooks SET CurrentTimeSeconds = :currentTimeSeconds, Progress = :progress, IsFinished = :isFinished WHERE Id = :id")
    suspend fun updateProgress(id: String, currentTimeSeconds: Double, progress: Double, isFinished: Int)

    /** Dynamic filtered query — built by AudioBookRepository.getFilteredBooks(). */
    @RawQuery(observedEntities = [AudioBookEntity::class, PlaybackProgressEntity::class])
    suspend fun getFilteredBooks(query: SupportSQLiteQuery): List<RecentlyPlayedResult>

    /** Count all audiobooks in a library. */
    @Query("SELECT COUNT(*) FROM AudioBooks WHERE LibraryId = :libraryId")
    suspend fun countByLibrary(libraryId: String): Int

    /** Distinct series names for a library. */
    @Query("SELECT DISTINCT SeriesName FROM AudioBooks WHERE LibraryId = :libraryId AND ArchivedAt IS NULL AND SeriesName IS NOT NULL AND SeriesName != '' ORDER BY SeriesName")
    suspend fun getDistinctSeries(libraryId: String): List<String>

    /** Distinct authors for a library. */
    @Query("SELECT DISTINCT Author FROM AudioBooks WHERE LibraryId = :libraryId AND ArchivedAt IS NULL AND Author IS NOT NULL AND Author != '' ORDER BY Author")
    suspend fun getDistinctAuthors(libraryId: String): List<String>

    /** Distinct genres for a library (genres stored as JSON array). */
    @Query("SELECT DISTINCT GenresJson FROM AudioBooks WHERE LibraryId = :libraryId AND ArchivedAt IS NULL AND GenresJson IS NOT NULL AND GenresJson != '[]' AND GenresJson != ''")
    suspend fun getDistinctGenresJson(libraryId: String): List<String>
}
