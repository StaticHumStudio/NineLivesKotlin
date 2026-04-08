package com.ninelivesaudio.app.data.repository

import androidx.sqlite.db.SimpleSQLiteQuery
import com.ninelivesaudio.app.data.local.converter.toDomain
import com.ninelivesaudio.app.data.local.converter.toEntity
import com.ninelivesaudio.app.data.local.dao.AudioBookDao
import com.ninelivesaudio.app.data.remote.ApiService
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.util.toEpochMillis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

@Singleton
class AudioBookRepository @Inject constructor(
    private val audioBookDao: AudioBookDao,
    private val apiService: ApiService,
) {
    /** Observe all audiobooks (reactive). */
    fun observeAll(): Flow<List<AudioBook>> =
        audioBookDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    /** Observe audiobooks for a specific library. */
    fun observeByLibrary(libraryId: String): Flow<List<AudioBook>> =
        audioBookDao.observeByLibrary(libraryId).map { entities -> entities.map { it.toDomain() } }

    /** Observe a single audiobook. */
    fun observeById(id: String): Flow<AudioBook?> =
        audioBookDao.observeById(id).map { it?.toDomain() }

    /** Get all audiobooks from local DB (one-shot). */
    suspend fun getAll(): List<AudioBook> =
        audioBookDao.getAll().map { it.toDomain() }

    /** Get audiobooks by library (one-shot). */
    suspend fun getByLibrary(libraryId: String): List<AudioBook> =
        audioBookDao.getByLibrary(libraryId).map { it.toDomain() }

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
        val sql = buildString {
            append("SELECT ab.*, pp.UpdatedAt AS lastPlayedAt FROM AudioBooks ab")
            append(" LEFT JOIN PlaybackProgress pp ON ab.Id = pp.AudioBookId")
            append(" WHERE ab.LibraryId = ?")

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
            if (searchQuery.isNotBlank()) {
                append(" AND (ab.Title LIKE ? OR ab.Author LIKE ? OR ab.SeriesName LIKE ? OR ab.Narrator LIKE ?)")
            }

            append(" ORDER BY ab.Title")
        }

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
                val local = localBooks[remoteBook.id]
                if (local != null) {
                    // Preserve local download state
                    val withDownload = if (local.isDownloaded == 1) {
                        remoteBook.copy(isDownloaded = true, localPath = local.localPath)
                    } else remoteBook

                    // Preserve local progress if it's ahead of server (offline playback)
                    val localTime = local.currentTimeSeconds
                    val remoteTime = withDownload.currentTime.inWholeMilliseconds / 1000.0
                    if (localTime > remoteTime) {
                        withDownload.copy(
                            currentTime = localTime.seconds,
                            progress = local.progress,
                            isFinished = local.isFinished == 1,
                        )
                    } else withDownload
                } else {
                    remoteBook
                }
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

    /** Delete all audiobooks from local DB. */
    suspend fun deleteAll() {
        audioBookDao.deleteAll()
    }
}
