package com.ninelivesaudio.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.ninelivesaudio.app.data.local.entity.AudioBookEntity
import com.ninelivesaudio.app.data.local.entity.AutoBrowseRow
import com.ninelivesaudio.app.data.local.entity.BookProgressState
import com.ninelivesaudio.app.data.local.entity.SyncMergeState
import com.ninelivesaudio.app.data.local.entity.LocalCatalogEntry
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

    @Query("SELECT * FROM AudioBooks WHERE LibraryId = :libraryId AND IsLocal = :isLocal ORDER BY Title")
    suspend fun getByLibraryAndSource(libraryId: String, isLocal: Int): List<AudioBookEntity>

    @Query("SELECT * FROM AudioBooks WHERE IsLocal = :isLocal ORDER BY Title")
    suspend fun getBySource(isLocal: Int): List<AudioBookEntity>

    @Query("SELECT * FROM AudioBooks WHERE Id = :id")
    suspend fun getById(id: String): AudioBookEntity?

    /** Batch lookup by IDs — used by syncLibraryItems to preserve download state. */
    @Query("SELECT * FROM AudioBooks WHERE Id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<AudioBookEntity>

    /** Only the local-only fields a server sync keeps, for up to 500 IDs at a time. */
    @Query(
        "SELECT Id, IsDownloaded, LocalPath, LocalCoverPath, CurrentTimeSeconds, Progress, IsFinished, ArchivedAt " +
            "FROM AudioBooks WHERE Id IN (:ids)"
    )
    suspend fun getSyncMergeStates(ids: List<String>): List<SyncMergeState>

    /** Shelf progress for up to 500 IDs, for the progress pull's "anything changed?" check. */
    @Query("SELECT Id, CurrentTimeSeconds, Progress, IsFinished, DurationSeconds FROM AudioBooks WHERE Id IN (:ids)")
    suspend fun getProgressStates(ids: List<String>): List<BookProgressState>

    /** How many SERVER books one library has cached, downloads included. */
    @Query("SELECT COUNT(*) FROM AudioBooks WHERE LibraryId = :libraryId AND IsLocal = 0")
    suspend fun countServerBooksByLibrary(libraryId: String): Int

    /** Which of up to 500 IDs are already cached as SERVER books of this library. */
    @Query("SELECT Id FROM AudioBooks WHERE LibraryId = :libraryId AND IsLocal = 0 AND Id IN (:ids)")
    suspend fun getServerIdsInLibrary(libraryId: String, ids: List<String>): List<String>

    /** Every stored localPath except [excludeId]'s, for the shared-folder check on cancel. */
    @Query("SELECT LocalPath FROM AudioBooks WHERE Id != :excludeId AND LocalPath IS NOT NULL AND LocalPath != ''")
    suspend fun getLocalPathsExcept(excludeId: String): List<String>

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

    /** Non-downloaded SERVER row IDs in one library for bind-safe reconciliation. */
    @Query("SELECT Id FROM AudioBooks WHERE LibraryId = :libraryId AND IsLocal = 0 AND IsDownloaded = 0")
    suspend fun getNonDownloadedServerIdsByLibrary(libraryId: String): List<String>

    /**
     * Deletes a bind-safe chunk of non-downloaded SERVER rows. [ids] are
     * kept library-scoped so a rehomed row is never deleted by a stale sync.
     */
    @Query("DELETE FROM AudioBooks WHERE LibraryId = :libraryId AND IsLocal = 0 AND IsDownloaded = 0 AND Id IN (:ids)")
    suspend fun deleteServerBooksByIds(libraryId: String, ids: List<String>)

    /**
     * Prune every cached, non-downloaded SERVER row for a library a complete
     * fetch confirmed is now empty (issue #14, PR #30 review — a previously
     * populated library that genuinely emptied out must not keep showing
     * its stale cached rows on the shelf). Downloaded books are exempt, same
     * as [deleteServerBooksByIds].
     */
    @Query("DELETE FROM AudioBooks WHERE LibraryId = :libraryId AND IsLocal = 0 AND IsDownloaded = 0")
    suspend fun deleteServerBooksByLibrary(libraryId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM AudioBooks WHERE LibraryId = :libraryId AND IsLocal = 0 AND IsDownloaded = 1)")
    suspend fun hasDownloadedServerBooks(libraryId: String): Boolean

    /** Ids of all LOCAL books in a library (live or archived). */
    @Query("SELECT Id FROM AudioBooks WHERE LibraryId = :libraryId AND IsLocal = 1")
    suspend fun getLocalIdsByLibrary(libraryId: String): List<String>

    /** Ids of the LOCAL books a library currently shows (archived ones excluded). */
    @Query("SELECT Id FROM AudioBooks WHERE LibraryId = :libraryId AND IsLocal = 1 AND ArchivedAt IS NULL")
    suspend fun getLiveLocalIdsByLibrary(libraryId: String): List<String>

    /**
     * Every LOCAL book's library, archived flag, and shelf fields, re-emitted
     * whenever the table changes. Progress columns stay out so a caller that
     * drops repeats hears about imports, archives, and rescans, not position
     * saves.
     */
    @Query(
        """
        SELECT Id AS id, LibraryId AS libraryId, ArchivedAt IS NOT NULL AS isArchived,
            Title AS title, Author AS author, Narrator AS narrator,
            CoverPath AS coverPath, LocalCoverPath AS localCoverPath,
            DurationSeconds AS durationSeconds, AddedAt AS addedAt,
            SeriesName AS seriesName, SeriesSequence AS seriesSequence, GenresJson AS genresJson,
            length(ChaptersJson) AS chaptersJsonLength
        FROM AudioBooks WHERE IsLocal = 1
        """
    )
    fun observeLocalCatalog(): Flow<List<LocalCatalogEntry>>

    /** Ids of the archived (soft-deleted) LOCAL books in a library. */
    @Query("SELECT Id FROM AudioBooks WHERE LibraryId = :libraryId AND IsLocal = 1 AND ArchivedAt IS NOT NULL")
    suspend fun getArchivedLocalIdsByLibrary(libraryId: String): List<String>

    /** Soft-delete: stamp ArchivedAt on the given books (skips already-archived). */
    @Query("UPDATE AudioBooks SET ArchivedAt = :archivedAt WHERE Id IN (:ids) AND ArchivedAt IS NULL")
    suspend fun archiveByIds(ids: List<String>, archivedAt: Long)

    /** Carry a moved local book's place onto its new row (issue #20). */
    @Query(
        "UPDATE AudioBooks SET CurrentTimeSeconds = :currentTimeSeconds, " +
            "Progress = :progress, IsFinished = :isFinished WHERE Id = :id"
    )
    suspend fun updateLocalPosition(
        id: String,
        currentTimeSeconds: Double,
        progress: Double,
        isFinished: Int,
    )

    /** Repoint a book's cover to a durable path (persisting a SAF folder cover). */
    @Query("UPDATE AudioBooks SET CoverPath = :coverPath WHERE Id = :id")
    suspend fun updateCoverPath(id: String, coverPath: String)

    // ─── Readers outside the Library shelf ───────────────────────────────
    //
    // Screens and services that only need a handful of books ask for those
    // books, not the whole library. Each query here is bounded by an id list,
    // a LIMIT, or a small projection.

    /** Up to 500 books by id, kept to one library and source (the Dossier's listened books). */
    @Query("SELECT * FROM AudioBooks WHERE LibraryId = :libraryId AND IsLocal = :isLocal AND Id IN (:ids)")
    suspend fun getByIdsInLibraryAndSource(libraryId: String, isLocal: Int, ids: List<String>): List<AudioBookEntity>

    /**
     * One page of Android Auto's Library list: live books in one library and
     * source, A to Z, light columns only. Id breaks title ties so pages never
     * overlap or skip.
     */
    @Query(
        """
        SELECT Id AS id, Title AS title, Author AS author, Narrator AS narrator,
            CoverPath AS coverPath, LocalCoverPath AS localCoverPath, GenresJson AS genresJson
        FROM AudioBooks
        WHERE LibraryId = :libraryId AND IsLocal = :isLocal AND ArchivedAt IS NULL
        ORDER BY Title COLLATE NOCASE, Id
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun getAutoBrowsePage(libraryId: String, isLocal: Int, limit: Int, offset: Int): List<AutoBrowseRow>

    /** Same as [getAutoBrowsePage], downloaded books only. */
    @Query(
        """
        SELECT Id AS id, Title AS title, Author AS author, Narrator AS narrator,
            CoverPath AS coverPath, LocalCoverPath AS localCoverPath, GenresJson AS genresJson
        FROM AudioBooks
        WHERE LibraryId = :libraryId AND IsLocal = :isLocal AND ArchivedAt IS NULL AND IsDownloaded = 1
        ORDER BY Title COLLATE NOCASE, Id
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun getAutoDownloadedPage(libraryId: String, isLocal: Int, limit: Int, offset: Int): List<AutoBrowseRow>

    @Query("DELETE FROM AudioBooks")
    suspend fun deleteAll()

    @Query("DELETE FROM AudioBooks WHERE IsLocal = 0")
    suspend fun deleteAudiobookshelf()

    /** Nine Lives — recently played books with their last-played timestamp. */
    @Query("""
        SELECT ab.*, pp.UpdatedAt AS lastPlayedAt
        FROM AudioBooks ab
        INNER JOIN PlaybackProgress pp ON ab.Id = pp.AudioBookId
        WHERE ab.ArchivedAt IS NULL
        ORDER BY pp.UpdatedAt DESC
        LIMIT :limit
    """)
    suspend fun getRecentlyPlayed(limit: Int = 9): List<RecentlyPlayedResult>

    /** Nine Lives — observable version for reactive UI. */
    @Query("""
        SELECT ab.*, pp.UpdatedAt AS lastPlayedAt
        FROM AudioBooks ab
        INNER JOIN PlaybackProgress pp ON ab.Id = pp.AudioBookId
        WHERE ab.ArchivedAt IS NULL
        ORDER BY pp.UpdatedAt DESC
        LIMIT :limit
    """)
    fun observeRecentlyPlayed(limit: Int = 9): Flow<List<RecentlyPlayedResult>>

    /** Nine Lives — recently played books filtered by library. */
    @Query("""
        SELECT ab.*, pp.UpdatedAt AS lastPlayedAt
        FROM AudioBooks ab
        INNER JOIN PlaybackProgress pp ON ab.Id = pp.AudioBookId
        WHERE ab.LibraryId = :libraryId AND ab.ArchivedAt IS NULL
        ORDER BY pp.UpdatedAt DESC
        LIMIT :limit
    """)
    suspend fun getRecentlyPlayedByLibrary(libraryId: String, limit: Int = 9): List<RecentlyPlayedResult>

    /** Android Auto variant applies source scope before LIMIT. */
    @Query("""
        SELECT ab.*, pp.UpdatedAt AS lastPlayedAt
        FROM AudioBooks ab
        INNER JOIN PlaybackProgress pp ON ab.Id = pp.AudioBookId
        WHERE ab.LibraryId = :libraryId AND ab.IsLocal = :isLocal AND ab.ArchivedAt IS NULL
        ORDER BY pp.UpdatedAt DESC
        LIMIT :limit
    """)
    suspend fun getRecentlyPlayedByLibraryAndSource(
        libraryId: String,
        isLocal: Int,
        limit: Int,
    ): List<RecentlyPlayedResult>

    /** Nine Lives — observable recently played books filtered by library. */
    @Query("""
        SELECT ab.*, pp.UpdatedAt AS lastPlayedAt
        FROM AudioBooks ab
        INNER JOIN PlaybackProgress pp ON ab.Id = pp.AudioBookId
        WHERE ab.LibraryId = :libraryId AND ab.ArchivedAt IS NULL
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

    /** Count live audiobooks in a library (drives the empty-state copy, so it
     *  excludes archived books — an archive-only library reads as empty). */
    /** Every book the app currently holds, for the bug report's stored-state line. */
    @Query("SELECT COUNT(*) FROM AudioBooks WHERE ArchivedAt IS NULL")
    suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM AudioBooks WHERE LibraryId = :libraryId AND ArchivedAt IS NULL")
    suspend fun countByLibrary(libraryId: String): Int

    @Query("SELECT COUNT(*) FROM AudioBooks WHERE LibraryId = :libraryId AND IsLocal = :isLocal AND ArchivedAt IS NULL")
    suspend fun countByLibraryAndSource(libraryId: String, isLocal: Int): Int

    @Query("SELECT COUNT(*) FROM AudioBooks WHERE LibraryId = :libraryId AND IsLocal = :isLocal AND ArchivedAt IS NULL AND IsDownloaded = 1")
    suspend fun countDownloadedByLibrary(libraryId: String, isLocal: Int): Int

    @Query("""
        SELECT COUNT(DISTINCT ab.Id)
        FROM AudioBooks ab
        INNER JOIN PlaybackProgress pp ON ab.Id = pp.AudioBookId
        WHERE ab.LibraryId = :libraryId AND ab.IsLocal = :isLocal AND ab.ArchivedAt IS NULL
    """)
    suspend fun countRecentlyPlayedByLibrary(libraryId: String, isLocal: Int): Int

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
