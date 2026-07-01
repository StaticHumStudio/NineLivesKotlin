package com.ninelivesaudio.app.data.repository

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import com.ninelivesaudio.app.data.local.converter.toDomain
import com.ninelivesaudio.app.data.local.converter.toEntity
import com.ninelivesaudio.app.data.local.dao.AudioBookDao
import com.ninelivesaudio.app.data.local.dao.LocalBookmarkDao
import com.ninelivesaudio.app.data.local.dao.LocalListeningSessionDao
import com.ninelivesaudio.app.data.local.dao.PlaybackProgressDao
import com.ninelivesaudio.app.data.local.entity.AudioBookEntity
import com.ninelivesaudio.app.data.remote.ApiService
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.util.toEpochMillis
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

@Singleton
class AudioBookRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioBookDao: AudioBookDao,
    private val apiService: ApiService,
    private val localListeningSessionDao: LocalListeningSessionDao,
    private val localBookmarkDao: LocalBookmarkDao,
    private val playbackProgressDao: PlaybackProgressDao,
) {
    /** Observe all audiobooks (reactive). */
    fun observeAll(): Flow<List<AudioBook>> =
        audioBookDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    /** Observe audiobooks for a specific library. */
    fun observeByLibrary(libraryId: String): Flow<List<AudioBook>> =
        audioBookDao.observeByLibrary(libraryId).map { entities -> entities.map { it.toDomain() } }

    /** Observe all local-source audiobooks. */
    fun observeLocalBooks(): Flow<List<AudioBook>> =
        audioBookDao.observeBySource(isLocal = 1).map { entities -> entities.map { it.toDomain() } }

    /** Observe a single audiobook. */
    fun observeById(id: String): Flow<AudioBook?> =
        audioBookDao.observeById(id).map { it?.toDomain() }

    /** Get all audiobooks from local DB (one-shot). */
    suspend fun getAll(): List<AudioBook> =
        audioBookDao.getAll().map { it.toDomain() }

    /** Get audiobooks by library (one-shot). */
    suspend fun getByLibrary(libraryId: String): List<AudioBook> =
        audioBookDao.getByLibrary(libraryId).map { it.toDomain() }

    /** Get all local-source audiobooks (one-shot). */
    suspend fun getLocalBooks(): List<AudioBook> =
        audioBookDao.getBySource(isLocal = 1).map { it.toDomain() }

    /** Get audiobooks by library with last-played timestamps enriched. */
    suspend fun getByLibraryWithLastPlayed(libraryId: String): List<AudioBook> =
        audioBookDao.getByLibraryWithLastPlayed(libraryId).map { result ->
            result.audioBook.toDomain().copy(
                lastPlayedAt = result.lastPlayedAt?.toEpochMillis()
            )
        }

    /** Get a single audiobook by ID. */
    suspend fun getById(id: String): AudioBook? =
        audioBookDao.getById(id)?.toDomain()

    /** Search audiobooks by title or author. */
    suspend fun search(query: String): List<AudioBook> {
        val normalized = query.trim()
        if (normalized.isEmpty()) return getAll()
        return audioBookDao.search(normalized).map { it.toDomain() }
    }

    /** Get recently played audiobooks for Nine Lives home screen. */
    suspend fun getRecentlyPlayed(limit: Int = 9): List<Pair<AudioBook, Long>> =
        audioBookDao.getRecentlyPlayed(limit).map { result ->
            val book = result.audioBook.toDomain()
            val lastPlayed = result.lastPlayedAt?.toEpochMillis() ?: 0L
            book to lastPlayed
        }

    /** Observe recently played audiobooks (reactive). */
    fun observeRecentlyPlayed(limit: Int = 9): Flow<List<Pair<AudioBook, Long>>> =
        audioBookDao.observeRecentlyPlayed(limit).map { results ->
            results.map { result ->
                val book = result.audioBook.toDomain()
                val lastPlayed = result.lastPlayedAt?.toEpochMillis() ?: 0L
                book to lastPlayed
            }
        }

    /**
     * Get filtered books for a library, pushing WHERE clauses to SQL.
     * Eliminates the need to hold all books in memory for filtering.
     *
     * @param tab 0=All, 1=InProgress, 2=Completed, 3=Downloaded
     * @param hideFinished whether to exclude finished books
     * @param downloadedOnly whether to show only downloaded books
     * @param searchQuery optional search text (matches title, author, series, narrator)
     */
    suspend fun getFilteredBooks(
        libraryId: String,
        tab: Int = 0,
        hideFinished: Boolean = false,
        downloadedOnly: Boolean = false,
        searchQuery: String = "",
    ): List<AudioBook> {
        val sql = buildLibrarySql(tab, hideFinished, downloadedOnly, searchQuery.isNotBlank())

        val args = mutableListOf<Any>(libraryId)
        if (searchQuery.isNotBlank()) {
            val pattern = "%${searchQuery}%"
            args.addAll(listOf(pattern, pattern, pattern, pattern))
        }

        val results = audioBookDao.getFilteredBooks(SimpleSQLiteQuery(sql, args.toTypedArray()))
        return results.map { result ->
            result.audioBook.toDomain().copy(
                lastPlayedAt = result.lastPlayedAt?.toEpochMillis()
            )
        }
    }

    /** Count all audiobooks in a library. */
    suspend fun countByLibrary(libraryId: String): Int =
        audioBookDao.countByLibrary(libraryId)

    /** Get distinct series names for a library. */
    suspend fun getDistinctSeries(libraryId: String): List<String> =
        audioBookDao.getDistinctSeries(libraryId)

    /** Get distinct authors for a library. */
    suspend fun getDistinctAuthors(libraryId: String): List<String> =
        audioBookDao.getDistinctAuthors(libraryId)

    /** Get distinct genres for a library (parsed from JSON arrays). */
    suspend fun getDistinctGenres(libraryId: String): List<String> {
        val jsonStrings = audioBookDao.getDistinctGenresJson(libraryId)
        return jsonStrings
            .flatMap { json ->
                try {
                    Json.decodeFromString<List<String>>(json)
                } catch (_: Exception) {
                    emptyList()
                }
            }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    /** Fetch all items for a library from server and save to local DB. */
    suspend fun syncLibraryItems(libraryId: String): List<AudioBook> {
        val remote = apiService.getLibraryItems(libraryId)
        if (remote.isNotEmpty()) {
            // Preserve local download info when syncing.
            // Query by book IDs (not by libraryId) so we find existing entries
            // regardless of how they were originally saved — prevents full-row
            // REPLACE from wiping isDownloaded/localPath on libraryId mismatch.
            val localBooks = audioBookDao.getByIds(remote.map { it.id }).associateBy { it.id }
            val merged = remote.map { remoteBook ->
                mergeSyncedBook(remoteBook, localBooks[remoteBook.id])
            }
            audioBookDao.upsertAll(merged.map { it.toEntity() })
            return merged
        }
        return remote
    }

    /** Fetch expanded item details from server. */
    suspend fun fetchFromServer(itemId: String): AudioBook? {
        return apiService.getAudioBook(itemId)
    }

    /** Save a single audiobook to local DB. */
    suspend fun save(audioBook: AudioBook) {
        audioBookDao.upsert(audioBook.toEntity())
    }

    /** Save multiple audiobooks to local DB. */
    suspend fun saveAll(audioBooks: List<AudioBook>) {
        audioBookDao.upsertAll(audioBooks.map { it.toEntity() })
    }

    /** Import scanned Local Library books into one local library. */
    suspend fun importLocalBooks(libraryId: String, books: List<AudioBook>) {
        if (books.isEmpty()) return
        val existingById = audioBookDao.getByIds(books.map { it.id }).associateBy { it.id }
        audioBookDao.upsertAll(
            books.map { book ->
                val existing = existingById[book.id]
                book.copy(
                    libraryId = libraryId,
                    isLocal = true,
                    isDownloaded = true,
                    archivedAt = null, // present in this scan => restore if it was archived
                    // Keep the existing durable cover if this scan resolved none
                    // (e.g. the folder cover.jpg was removed or art extraction
                    // hiccuped) — a REPLACE upsert would otherwise null it out
                    // and orphan the local_covers file still on disk.
                    coverPath = book.coverPath ?: existing?.coverPath,
                    localCoverPath = book.localCoverPath ?: existing?.localCoverPath,
                    currentTime = existing?.currentTimeSeconds?.seconds ?: book.currentTime,
                    progress = existing?.progress ?: book.progress,
                    isFinished = existing?.isFinished?.let { it == 1 } ?: book.isFinished,
                ).toEntity()
            }
        )
    }

    /**
     * Archive local books that were not present in the latest scan (LOCAL-mode
     * soft-delete) instead of hard-deleting them, so their cover + history
     * survive. A returning folder re-imports with the same id and clears the
     * flag (see importLocalBooks).
     */
    suspend fun removeMissingLocalBooks(libraryId: String, scannedIds: List<String>) {
        val existing = audioBookDao.getLocalIdsByLibrary(libraryId)
        val toArchive = idsToArchive(existing, scannedIds)
        if (toArchive.isNotEmpty()) {
            audioBookDao.archiveByIds(toArchive, System.currentTimeMillis())
        }
    }

    /**
     * Make every local cover in [libraryId] durable before archiving. Books
     * scanned before folder-cover persistence existed still hold a `content://`
     * SAF cover that dies once the folder's permission is released; copy those
     * to app-private storage while access is still granted. [persist] performs
     * the copy (LocalMetadataExtractor.persistFolderCover): it returns a durable
     * path, the same path when already durable, or null when unreadable, so this
     * only rewrites CoverPath when it actually changed. Best-effort.
     */
    suspend fun persistFolderCovers(
        libraryId: String,
        persist: (coverUri: String?, bookId: String) -> String?,
    ) {
        audioBookDao.getByLibrary(libraryId)
            .filter { it.isLocal == 1 }
            .forEach { entity ->
                val durable = persist(entity.coverPath, entity.id)
                if (durable != null && durable != entity.coverPath) {
                    audioBookDao.updateCoverPath(entity.id, durable)
                }
            }
    }

    /**
     * Permanently delete a LOCAL book and everything tied to it: the row, its
     * playback progress, its listening sessions, its bookmarks, and the cached
     * cover file. PlaybackProgress is keyed by the (content-deterministic) book
     * id, so leaving it would resurrect old position/finished state if the same
     * folder is re-added later.
     */
    suspend fun deleteLocalBookForever(bookId: String) {
        audioBookDao.deleteById(bookId)
        playbackProgressDao.deleteByAudioBookId(bookId)
        localListeningSessionDao.deleteByAudioBookId(bookId)
        localBookmarkDao.deleteAllForBook(bookId)
        runCatching {
            java.io.File(java.io.File(context.filesDir, "local_covers"), "$bookId.jpg").delete()
        }
    }

    /** Delete all audiobooks from local DB. */
    suspend fun deleteAll() {
        audioBookDao.deleteAll()
    }
}

/**
 * Merge a server book with its local row during sync, preserving local-only
 * state the server does not know about: the download flag, the on-disk audio
 * path, the cover persisted at download time, and any local playback progress
 * that is ahead of the server. Pure, so it is unit-testable without the DB.
 */
internal fun mergeSyncedBook(remote: AudioBook, local: AudioBookEntity?): AudioBook {
    if (local == null) return remote

    val withDownload = if (local.isDownloaded == 1) {
        remote.copy(
            isDownloaded = true,
            localPath = local.localPath,
            localCoverPath = local.localCoverPath,
        )
    } else remote

    // Preserve local progress if it's ahead of the server (offline playback).
    val localTime = local.currentTimeSeconds
    val remoteTime = withDownload.currentTime.inWholeMilliseconds / 1000.0
    val merged = if (localTime > remoteTime) {
        withDownload.copy(
            currentTime = localTime.seconds,
            progress = local.progress,
            isFinished = local.isFinished == 1,
        )
    } else withDownload

    // Carry the local archive flag so a server sync can't resurrect an archived
    // book (defensive: only local books archive today and they don't sync, but
    // keep the invariant if archiving is ever extended to server books).
    return merged.copy(archivedAt = local.archivedAt)
}

/**
 * The local book ids to archive after a scan: those that exist locally but were
 * not seen in the latest scan. Pure, so it is unit-testable without the DB.
 */
internal fun idsToArchive(existingLocalIds: List<String>, scannedIds: List<String>): List<String> {
    val scanned = scannedIds.toHashSet()
    return existingLocalIds.filterNot { it in scanned }
}

/**
 * Build the library shelf query. Pure, so the archive visibility rules are
 * unit-testable without the DB. The Archive tab (int 4) shows only archived
 * books; every other tab shows only live books.
 */
internal fun buildLibrarySql(
    tab: Int,
    hideFinished: Boolean,
    downloadedOnly: Boolean,
    hasSearch: Boolean,
): String = buildString {
    append("SELECT ab.*, pp.UpdatedAt AS lastPlayedAt FROM AudioBooks ab")
    append(" LEFT JOIN PlaybackProgress pp ON ab.Id = pp.AudioBookId")
    append(" WHERE ab.LibraryId = ?")

    // Archive visibility: the Archive tab shows only archived; every other tab
    // shows only live books.
    if (tab == 4) append(" AND ab.ArchivedAt IS NOT NULL")
    else append(" AND ab.ArchivedAt IS NULL")

    // Tab filters — progressPercent: if Progress <= 1.0 then Progress*100 else Progress
    when (tab) {
        1 -> { // InProgress
            append(" AND ab.Progress > 0")
            append(" AND ab.IsFinished = 0")
            append(" AND (CASE WHEN ab.Progress <= 1.0 THEN ab.Progress * 100.0 ELSE ab.Progress END) < 99.5")
        }
        2 -> { // Completed
            append(" AND (ab.IsFinished = 1 OR ab.Progress >= 1.0")
            append(" OR (CASE WHEN ab.Progress <= 1.0 THEN ab.Progress * 100.0 ELSE ab.Progress END) >= 99.5)")
        }
        3 -> { // Downloaded
            append(" AND ab.IsDownloaded = 1")
        }
    }

    // Hide finished
    if (hideFinished) {
        append(" AND ab.IsFinished = 0 AND ab.Progress < 1.0")
        append(" AND (CASE WHEN ab.Progress <= 1.0 THEN ab.Progress * 100.0 ELSE ab.Progress END) < 99.5")
    }

    // Downloaded only
    if (downloadedOnly) {
        append(" AND ab.IsDownloaded = 1")
    }

    // Search
    if (hasSearch) {
        append(" AND (ab.Title LIKE ? OR ab.Author LIKE ? OR ab.SeriesName LIKE ? OR ab.Narrator LIKE ?)")
    }

    append(" ORDER BY ab.Title")
}
