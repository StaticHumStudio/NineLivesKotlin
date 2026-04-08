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

    @Query("SELECT * FROM AudioBooks WHERE LibraryId = :libraryId ORDER BY Title")
    suspend fun getByLibrary(libraryId: String): List<AudioBookEntity>

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

    @Query("DELETE FROM AudioBooks")
    suspend fun deleteAll()

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
    @Query("SELECT DISTINCT SeriesName FROM AudioBooks WHERE LibraryId = :libraryId AND SeriesName IS NOT NULL AND SeriesName != '' ORDER BY SeriesName")
    suspend fun getDistinctSeries(libraryId: String): List<String>

    /** Distinct authors for a library. */
    @Query("SELECT DISTINCT Author FROM AudioBooks WHERE LibraryId = :libraryId AND Author IS NOT NULL AND Author != '' ORDER BY Author")
    suspend fun getDistinctAuthors(libraryId: String): List<String>

    /** Distinct genres for a library (genres stored as JSON array). */
    @Query("SELECT DISTINCT GenresJson FROM AudioBooks WHERE LibraryId = :libraryId AND GenresJson IS NOT NULL AND GenresJson != '[]' AND GenresJson != ''")
    suspend fun getDistinctGenresJson(libraryId: String): List<String>
}
