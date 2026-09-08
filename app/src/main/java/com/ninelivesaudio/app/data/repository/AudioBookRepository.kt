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
import com.ninelivesaudio.app.data.local.entity.RecentlyPlayedResult
import com.ninelivesaudio.app.data.remote.ApiService
import com.ninelivesaudio.app.data.remote.ActiveRemoteScope
import com.ninelivesaudio.app.data.remote.RemoteResult
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.util.toEpochMillis
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    private val syncLibraryItemsMutex = Mutex()

    /** Observe all audiobooks (reactive). */
    fun observeAll(): Flow<List<AudioBook>> = flow {
        val scope = apiService.captureActiveRemoteScope() ?: return@flow
        emit(audioBookDao.getActiveCatalog(scope.idPrefix).map { it.toDomain() })
    }

    /** Observe audiobooks for a specific library. */
    fun observeByLibrary(libraryId: String): Flow<List<AudioBook>> = flow {
        val scope = activeRemoteScopeFor(libraryId)
        if (scope != null) {
            audioBookDao.observeActiveRemoteByLibrary(libraryId, scope.idPrefix)
                .collect { emit(it.map { entity -> entity.toDomain() }) }
        } else {
            audioBookDao.observeByLibrary(libraryId)
                .collect { rows -> emit(rows.filter { it.isLocal == 1 }.map { it.toDomain() }) }
        }
    }

    /** Observe all local-source audiobooks. */
    fun observeLocalBooks(): Flow<List<AudioBook>> =
        audioBookDao.observeBySource(isLocal = 1).map { entities -> entities.map { it.toDomain() } }

    /** Observe a single audiobook. */
    fun observeById(id: String): Flow<AudioBook?> =
        audioBookDao.observeById(id).map { it?.toDomain() }

    /** Get all audiobooks from local DB (one-shot). */
    suspend fun getAll(): List<AudioBook> =
        apiService.captureActiveRemoteScope()?.let { scope ->
            audioBookDao.getActiveCatalog(scope.idPrefix).map { it.toDomain() }
        } ?: audioBookDao.getBySource(1).map { it.toDomain() }

    /** Get audiobooks by library (one-shot). */
    suspend fun getByLibrary(libraryId: String): List<AudioBook> =
        activeRemoteScopeFor(libraryId)?.let { scope ->
            audioBookDao.getActiveRemoteByLibrary(libraryId, scope.idPrefix).map { it.toDomain() }
        } ?: audioBookDao.getByLibraryAndSource(libraryId, isLocal = 1).map { it.toDomain() }

    /** Get audiobooks for one library and source mode (one-shot). */
    suspend fun getByLibraryAndSource(libraryId: String, isLocal: Boolean): List<AudioBook> =
        if (isLocal) {
            audioBookDao.getByLibraryAndSource(libraryId, 1).map { it.toDomain() }
        } else {
            activeRemoteScopeFor(libraryId)?.let { scope ->
                audioBookDao.getActiveRemoteByLibrary(libraryId, scope.idPrefix).map { it.toDomain() }
            } ?: emptyList()
        }

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
        activeRemoteScopeFor(id)?.let { scope ->
            audioBookDao.getById(id)?.takeIf { it.isLocal == 0 && it.id.startsWith(scope.idPrefix) }?.toDomain()
        } ?: audioBookDao.getById(id)?.takeIf { it.isLocal == 1 }?.toDomain()

    /** Search audiobooks by title or author. */
    suspend fun search(query: String): List<AudioBook> {
        val normalized = query.trim()
        val scope = apiService.captureActiveRemoteScope() ?: return emptyList()
        return audioBookDao.searchActiveRemote(normalized, scope.idPrefix).map { it.toDomain() }
    }

    /** Get recently played audiobooks for Nine Lives home screen. */
    suspend fun getRecentlyPlayed(limit: Int = 9): List<Pair<AudioBook, Long>> =
        audioBookDao.getRecentlyPlayed(limit).map { result ->
            val book = result.audioBook.toDomain()
            val lastPlayed = result.lastPlayedAt?.toEpochMillis() ?: 0L
            book to lastPlayed
        }

    suspend fun getRecentlyPlayedByLibrary(
        libraryId: String,
        limit: Int = 9,
    ): List<Pair<AudioBook, Long>> =
        activeRemoteScopeFor(libraryId)?.let { scope ->
            audioBookDao.getActiveRemoteRecentlyPlayedByLibrary(libraryId, scope.idPrefix, limit)
        }?.map { result ->
            val book = result.audioBook.toDomain()
            val lastPlayed = result.lastPlayedAt?.toEpochMillis() ?: 0L
            book to lastPlayed
        } ?: emptyList()

    suspend fun getRecentlyPlayedForAuto(
        libraryId: String,
        isLocal: Boolean,
        limit: Int,
    ): List<Pair<AudioBook, Long>> =
        (if (isLocal) {
            audioBookDao.getRecentlyPlayedByLibraryAndSource(libraryId, 1, limit)
        } else {
            activeRemoteScopeFor(libraryId)?.let {
                audioBookDao.getActiveRemoteRecentlyPlayedByLibrary(libraryId, it.idPrefix, limit)
            } ?: emptyList()
        }).map { result ->
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

    suspend fun observeRecentlyPlayedRows(
        libraryId: String,
        isLocal: Boolean,
        limit: Int,
    ): Flow<List<RecentlyPlayedResult>> = if (isLocal) {
        audioBookDao.observeRecentlyPlayedByLibrary(libraryId, limit)
    } else {
        activeRemoteScopeFor(libraryId)?.let {
            audioBookDao.observeActiveRemoteRecentlyPlayedByLibrary(libraryId, it.idPrefix, limit)
        } ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }

    suspend fun getRecentlyPlayedRows(
        libraryId: String,
        isLocal: Boolean,
        limit: Int,
    ): List<RecentlyPlayedResult> = if (isLocal) {
        audioBookDao.getRecentlyPlayedByLibrary(libraryId, limit)
    } else {
        activeRemoteScopeFor(libraryId)?.let {
            audioBookDao.getActiveRemoteRecentlyPlayedByLibrary(libraryId, it.idPrefix, limit)
        } ?: emptyList()
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
        val scope = activeRemoteScopeFor(libraryId)
        if (scope == null && libraryId.startsWith("nlr1:")) return emptyList()
        val sql = buildLibrarySql(tab, hideFinished, downloadedOnly, searchQuery.isNotBlank(), scope?.idPrefix)

        val args = mutableListOf<Any>(libraryId)
        if (scope != null) args.addAll(listOf(scope.idPrefix, scope.idPrefix))
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
        activeRemoteScopeFor(libraryId)?.let { audioBookDao.countActiveRemoteByLibrary(libraryId, it.idPrefix) }
            ?: audioBookDao.countByLibraryAndSource(libraryId, 1)

    suspend fun countForAuto(libraryId: String, isLocal: Boolean): Int =
        audioBookDao.countByLibraryAndSource(libraryId, if (isLocal) 1 else 0)

    suspend fun countDownloadedForAuto(libraryId: String, isLocal: Boolean): Int =
        audioBookDao.countDownloadedByLibrary(libraryId, if (isLocal) 1 else 0)

    suspend fun countRecentlyPlayedForAuto(libraryId: String, isLocal: Boolean): Int =
        audioBookDao.countRecentlyPlayedByLibrary(libraryId, if (isLocal) 1 else 0)

    /** Get distinct series names for a library. */
    suspend fun getDistinctSeries(libraryId: String): List<String> =
        activeRemoteScopeFor(libraryId)?.let { audioBookDao.getDistinctActiveRemoteSeries(libraryId, it.idPrefix) }
            ?: audioBookDao.getDistinctSeries(libraryId).takeIf { !libraryId.startsWith("nlr1:") }.orEmpty()

    /** Get distinct authors for a library. */
    suspend fun getDistinctAuthors(libraryId: String): List<String> =
        activeRemoteScopeFor(libraryId)?.let { audioBookDao.getDistinctActiveRemoteAuthors(libraryId, it.idPrefix) }
            ?: audioBookDao.getDistinctAuthors(libraryId).takeIf { !libraryId.startsWith("nlr1:") }.orEmpty()

    /** Get distinct genres for a library (parsed from JSON arrays). */
    suspend fun getDistinctGenres(libraryId: String): List<String> {
        val jsonStrings = activeRemoteScopeFor(libraryId)?.let {
            audioBookDao.getDistinctActiveRemoteGenresJson(libraryId, it.idPrefix)
        } ?: audioBookDao.getDistinctGenresJson(libraryId).takeIf { !libraryId.startsWith("nlr1:") }.orEmpty()
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

    /**
     * Fetch all items for a library from server and save to local DB. A
     * partial fetch still saves what it got: some of the shelf beats none of
     * it, and the caller keeps the failure reason for the bug report.
     *
     * A complete (Ok) fetch, by contrast, is authoritative for this library
     * — including a confirmed-empty one, so the cache is reconciled to match
     * it exactly rather than just upserted-into. See [reconcileServerLibrary].
     */
    suspend fun syncLibraryItems(libraryId: String): RemoteResult<List<AudioBook>> {
        val scope = activeRemoteScopeFor(libraryId)
            ?: return RemoteResult.Failed("Catalog ID is not active")
        return syncLibraryItems(scope, libraryId)
    }

    private suspend fun syncLibraryItems(scope: ActiveRemoteScope, libraryId: String): RemoteResult<List<AudioBook>> = runSerializedLibraryItemSync(
        mutex = syncLibraryItemsMutex,
        libraryId = libraryId,
        fetchItems = { apiService.getLibraryItems(scope, libraryId) },
        mergeItems = { remote -> mergeSyncedBooks(remote, audioBookDao::getByIds) },
        upsertAll = { books -> audioBookDao.upsertAll(books.map { it.toEntity() }) },
        cachedNonDownloadedIds = { id -> audioBookDao.getNonDownloadedActiveServerIdsByLibrary(id, scope.idPrefix) },
        deleteByIds = { id, ids -> audioBookDao.deleteActiveServerBooksByIds(id, scope.idPrefix, ids) },
        deleteAllServerBooks = { id -> audioBookDao.deleteActiveServerBooksByLibrary(id, scope.idPrefix) },
    )

    /**
     * Applies an authoritative library-list removal through the same mutex as
     * item sync. A list response cannot prune this library while an older item
     * response is still in flight and about to upsert its stale books.
     */
    internal suspend fun pruneServerBooksForRemovedLibrary(scope: ActiveRemoteScope, libraryId: String): Boolean =
        runSerializedLibraryItemPrune(syncLibraryItemsMutex) {
            audioBookDao.deleteActiveServerBooksByLibrary(libraryId, scope.idPrefix)
            audioBookDao.hasDownloadedActiveServerBooks(libraryId, scope.idPrefix)
        }

    /** Fetch expanded item details from server. */
    suspend fun fetchFromServer(itemId: String): AudioBook? {
        val scope = activeRemoteScopeFor(itemId) ?: return null
        return apiService.getAudioBook(scope, itemId)
    }

    private suspend fun activeRemoteScopeFor(id: String): ActiveRemoteScope? =
        apiService.captureActiveRemoteScope()?.takeIf { it.decodeForEgress(id) != null }

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
        val existingById = fetchByIdChunks(books.map { it.id }, audioBookDao::getByIds).associateBy { it.id }
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

    /** Cascade-delete a set of local books (used by the archive sweep). */
    suspend fun deleteLocalBooksForever(ids: List<String>) {
        ids.forEach { deleteLocalBookForever(it) }
    }

    /** Ids of every local book in a library (live + archived). */
    suspend fun getLocalIds(libraryId: String): List<String> =
        audioBookDao.getLocalIdsByLibrary(libraryId)

    /** Ids of the archived local books in a library. */
    suspend fun getArchivedLocalIds(libraryId: String): List<String> =
        audioBookDao.getArchivedLocalIdsByLibrary(libraryId)

    /** Delete all audiobooks from local DB. */
    suspend fun deleteAll() {
        audioBookDao.deleteAll()
    }
}

private const val MAXIMUM_AUDIOBOOK_LOOKUP_BIND_COUNT = 500
private const val MAXIMUM_SERVER_BOOK_DELETE_BIND_COUNT = 499

private suspend fun <T> fetchByIdChunks(
    ids: List<String>,
    fetchByIds: suspend (List<String>) -> List<T>,
): List<T> {
    val rows = mutableListOf<T>()
    for (idChunk in ids.chunked(MAXIMUM_AUDIOBOOK_LOOKUP_BIND_COUNT)) {
        rows += fetchByIds(idChunk)
    }
    return rows
}

/**
 * Preserves local state while merging a server response, without binding more
 * than 500 book IDs in one cached-row lookup.
 */
internal suspend fun mergeSyncedBooks(
    remote: List<AudioBook>,
    getByIds: suspend (List<String>) -> List<AudioBookEntity>,
): List<AudioBook> {
    if (remote.isEmpty()) return emptyList()

    val localBooks = fetchByIdChunks(remote.map { it.id }, getByIds).associateBy { it.id }
    return remote.map { remoteBook -> mergeSyncedBook(remoteBook, localBooks[remoteBook.id]) }
}

/**
 * Reconciles the DAO's cached SERVER rows for one library against a
 * completed [syncLibraryItems] fetch, extracted so the branching is
 * unit-testable without Room (issue #14, PR #30 review).
 *
 * A complete ([isComplete]) fetch is authoritative for [libraryId] — the
 * cache should end up matching it exactly, including down to nothing when
 * [merged] is empty (a previously populated library that genuinely emptied
 * out). An incomplete (Partial) fetch is never authoritative: it upserts
 * what it got (some of the shelf beats none of it) but never prunes,
 * because a page it couldn't reach is not proof those books are gone —
 * that retention is unchanged from before this fix.
 *
 * [cachedNonDownloadedIds], [deleteByIds], and [deleteAllServerBooks] are
 * expected to scope to server (non-local) rows and exempt downloaded books.
 * A sync must never take away the user's own downloaded audio, only refresh
 * what the server confirms is still there.
 */
internal suspend fun reconcileServerLibrary(
    isComplete: Boolean,
    merged: List<AudioBook>,
    libraryId: String,
    upsertAll: suspend (List<AudioBook>) -> Unit,
    cachedNonDownloadedIds: suspend (libraryId: String) -> List<String>,
    deleteByIds: suspend (libraryId: String, ids: List<String>) -> Unit,
    deleteAllServerBooks: suspend (libraryId: String) -> Unit,
) {
    if (merged.isNotEmpty()) {
        upsertAll(merged)
    }
    if (!isComplete) return
    if (merged.isEmpty()) {
        deleteAllServerBooks(libraryId)
    } else {
        val keptIds = merged.mapTo(mutableSetOf()) { it.id }
        val missingIds = cachedNonDownloadedIds(libraryId).filterNot { it in keptIds }
        for (ids in missingIds.chunked(MAXIMUM_SERVER_BOOK_DELETE_BIND_COUNT)) {
            deleteByIds(libraryId, ids)
        }
    }
}

/**
 * Serializes one item's full fetch, merge, and reconciliation sequence. Both
 * SyncManager and LibraryViewModel can refresh the same library independently.
 * Holding [mutex] across the network fetch makes a later caller fetch only
 * after every earlier response has been applied, so an old response cannot
 * upsert books that a newer complete response already pruned.
 */
internal suspend fun runSerializedLibraryItemSync(
    mutex: Mutex,
    libraryId: String,
    fetchItems: suspend () -> RemoteResult<List<AudioBook>>,
    mergeItems: suspend (List<AudioBook>) -> List<AudioBook>,
    upsertAll: suspend (List<AudioBook>) -> Unit,
    cachedNonDownloadedIds: suspend (libraryId: String) -> List<String>,
    deleteByIds: suspend (libraryId: String, ids: List<String>) -> Unit,
    deleteAllServerBooks: suspend (libraryId: String) -> Unit,
): RemoteResult<List<AudioBook>> = mutex.withLock {
    val result = fetchItems()
    val remote = when (result) {
        is RemoteResult.Ok -> result.value
        is RemoteResult.Partial -> result.value
        is RemoteResult.Failed -> return@withLock result
    }
    val merged = mergeItems(remote)

    withContext(NonCancellable) {
        reconcileServerLibrary(
            isComplete = result is RemoteResult.Ok,
            merged = merged,
            libraryId = libraryId,
            upsertAll = upsertAll,
            cachedNonDownloadedIds = cachedNonDownloadedIds,
            deleteByIds = deleteByIds,
            deleteAllServerBooks = deleteAllServerBooks,
        )
    }

    when (result) {
        is RemoteResult.Partial -> RemoteResult.Partial(merged, result.reason)
        else -> RemoteResult.Ok(merged)
    }
}

/** Runs an authoritative item prune in the same ordering domain as item sync. */
internal suspend fun <T> runSerializedLibraryItemPrune(
    mutex: Mutex,
    prune: suspend () -> T,
): T = mutex.withLock { prune() }

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
    remoteIdPrefix: String? = null,
): String = buildString {
    append("SELECT ab.*, pp.UpdatedAt AS lastPlayedAt FROM AudioBooks ab")
    append(" LEFT JOIN PlaybackProgress pp ON ab.Id = pp.AudioBookId")
    append(" WHERE ab.LibraryId = ?")
    if (remoteIdPrefix != null) {
        append(" AND ab.IsLocal = 0 AND ab.Id LIKE ? || '%' AND ab.LibraryId LIKE ? || '%'")
    }

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
