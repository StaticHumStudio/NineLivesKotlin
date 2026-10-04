package com.ninelivesaudio.app.data.repository

import android.content.Context
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.ninelivesaudio.app.data.local.AppDatabase
import com.ninelivesaudio.app.data.local.searchTextLikePattern
import com.ninelivesaudio.app.data.local.converter.toDomain
import com.ninelivesaudio.app.data.local.converter.toEntity
import com.ninelivesaudio.app.data.local.dao.AudioBookDao
import com.ninelivesaudio.app.data.local.dao.LocalBookmarkDao
import com.ninelivesaudio.app.data.local.dao.LocalListeningSessionDao
import com.ninelivesaudio.app.data.local.dao.PlaybackProgressDao
import com.ninelivesaudio.app.data.local.entity.AudioBookEntity
import com.ninelivesaudio.app.data.local.entity.AutoBrowseRow
import com.ninelivesaudio.app.data.local.entity.LocalCatalogEntry
import com.ninelivesaudio.app.data.local.entity.PlaybackProgressEntity
import com.ninelivesaudio.app.data.local.entity.SHELF_BOOK_COLUMNS
import com.ninelivesaudio.app.data.local.entity.SyncMergeState
import com.ninelivesaudio.app.data.local.entity.toShelfBook
import com.ninelivesaudio.app.data.remote.ApiService
import com.ninelivesaudio.app.data.remote.RemoteResult
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.util.mapCooperatively
import com.ninelivesaudio.app.domain.util.toEpochMillis
import com.ninelivesaudio.app.service.shelfProgress
import com.ninelivesaudio.app.service.local.LocalBookFingerprint
import com.ninelivesaudio.app.service.local.folderNameOfTrackUri
import com.ninelivesaudio.app.service.local.matchMovedLocalBooks
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
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
    private val database: AppDatabase,
    private val watermarkStore: LibrarySyncWatermarkStore,
) {
    // Library syncs run here, not in whoever asked, so a caller that leaves
    // (the Library tab closing, a newer load replacing an older one) stops
    // waiting without throwing away a download someone else is sharing.
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncFlights = LibrarySyncSingleFlight<LibraryRefreshOutcome>(syncScope)

    // One lock per library and signed-in account (the same key as the
    // flights). Fetch, save, and prune for a library happen under its lock,
    // so an older response can never land after a newer complete one pruned.
    // Other libraries no longer wait behind a big one's download, and a new
    // account never waits behind (or shares) the previous account's sync,
    // whose writes stop once it notices the switch.
    private val libraryLocks = ConcurrentHashMap<String, Mutex>()
    private fun lockFor(flightKey: String): Mutex = libraryLocks.computeIfAbsent(flightKey) { Mutex() }

    private suspend fun isCurrent(identity: LibrarySyncIdentity): Boolean =
        watermarkStore.currentSyncIdentity() == identity

    // Background full downloads that keep failing wait before trying again.
    private val fullSyncFailures = ConcurrentHashMap<String, FullSyncFailures>()

    private val _booksSaved = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** A library id, each time a sync saves books for it (every page of a full download). */
    val booksSaved: SharedFlow<String> = _booksSaved.asSharedFlow()

    private val _deferredFullSyncs = MutableStateFlow<Set<String>>(emptySet())

    /** Libraries whose full download is waiting for an unmetered network. */
    val deferredFullSyncs: StateFlow<Set<String>> = _deferredFullSyncs.asStateFlow()

    /** Observe all audiobooks (reactive). */
    fun observeAll(): Flow<List<AudioBook>> =
        audioBookDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    /** Observe audiobooks for a specific library. */
    fun observeByLibrary(libraryId: String): Flow<List<AudioBook>> =
        audioBookDao.observeByLibrary(libraryId).map { entities -> entities.map { it.toDomain() } }

    /** Observe all local-source audiobooks. */
    fun observeLocalBooks(): Flow<List<AudioBook>> =
        audioBookDao.observeBySource(isLocal = 1).map { entities -> entities.map { it.toDomain() } }

    /** Observe which local books exist, which are archived, and their shelf fields (not progress). */
    fun observeLocalCatalog(): Flow<List<LocalCatalogEntry>> =
        audioBookDao.observeLocalCatalog()

    /** Observe a single audiobook. */
    fun observeById(id: String): Flow<AudioBook?> =
        audioBookDao.observeById(id).map { it?.toDomain() }

    /** Get audiobooks by library (one-shot). */
    suspend fun getByLibrary(libraryId: String): List<AudioBook> =
        audioBookDao.getByLibrary(libraryId).map { it.toDomain() }

    /** Get audiobooks for one library and source mode (one-shot). */
    suspend fun getByLibraryAndSource(libraryId: String, isLocal: Boolean): List<AudioBook> =
        audioBookDao.getByLibraryAndSource(libraryId, if (isLocal) 1 else 0).map { it.toDomain() }

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
        audioBookDao.getRecentlyPlayedByLibrary(libraryId, limit).map { result ->
            val book = result.audioBook.toDomain()
            val lastPlayed = result.lastPlayedAt?.toEpochMillis() ?: 0L
            book to lastPlayed
        }

    suspend fun getRecentlyPlayedForAuto(
        libraryId: String,
        isLocal: Boolean,
        limit: Int,
    ): List<Pair<AudioBook, Long>> =
        audioBookDao.getRecentlyPlayedByLibraryAndSource(
            libraryId = libraryId,
            isLocal = if (isLocal) 1 else 0,
            limit = limit,
        ).map { result ->
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
     * Returns light shelf books: description, audio files, and tags are
     * empty. Never pass one to [save] or [saveAll], read the book by id.
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
        val args = buildLibrarySqlArgs(libraryId, searchQuery)

        val results = audioBookDao.getFilteredBooks(SimpleSQLiteQuery(sql, args))
        // Shelf books carry no description, audio files, or tags. They are
        // for showing and navigating, never for saving back. See toShelfBook.
        return results.mapCooperatively { it.toShelfBook() }
    }

    /** Count all audiobooks in a library. */
    suspend fun countByLibrary(libraryId: String): Int =
        audioBookDao.countByLibrary(libraryId)

    suspend fun countForAuto(libraryId: String, isLocal: Boolean): Int =
        audioBookDao.countByLibraryAndSource(libraryId, if (isLocal) 1 else 0)

    suspend fun countDownloadedForAuto(libraryId: String, isLocal: Boolean): Int =
        audioBookDao.countDownloadedByLibrary(libraryId, if (isLocal) 1 else 0)

    suspend fun countRecentlyPlayedForAuto(libraryId: String, isLocal: Boolean): Int =
        audioBookDao.countRecentlyPlayedByLibrary(libraryId, if (isLocal) 1 else 0)

    /**
     * Download every item in a library and save it, a page at a time. This is
     * the full sync: sign-in, a server or account switch, a switch back from
     * Local mode, an explicit refresh (pull to refresh, Retry, Sync Now), and
     * the fallback whenever a change check cannot be trusted. It never waits
     * for an unmetered network or a failure backoff.
     *
     * A partial fetch still saves what it got: some of the shelf beats none
     * of it, and the caller keeps the failure reason for the bug report. A
     * complete (Ok) fetch is authoritative for this library, including a
     * confirmed-empty one, so the cache is pruned to match it exactly. See
     * [pruneServerLibraryTo]. A sync already running for this library is
     * shared, not repeated (see [LibrarySyncSingleFlight]).
     *
     * Returns how many books the fetch saved.
     */
    suspend fun syncLibraryItems(libraryId: String): RemoteResult<Int> {
        val identity = watermarkStore.currentSyncIdentity()
        val flightKey = identity.flightKey(libraryId)
        return syncFlights.run(flightKey, LibrarySyncKind.FULL) {
            lockFor(flightKey).withLock { runFullSyncLocked(libraryId, identity) }
        }.itemCountResult() ?: RemoteResult.Failed("sync did not run")
    }

    /** Whether this library has any server books cached, so a shelf exists to show. */
    internal suspend fun hasServerBooks(libraryId: String): Boolean =
        audioBookDao.countServerBooksByLibrary(libraryId) > 0

    /**
     * The background check for one library: ask the server whether anything
     * was added or removed since the last good sync (one tiny request), and
     * do the least work that brings the shelf back in line. Nothing new means
     * nothing fetched and nothing written. Added books are fetched on their
     * own. Removals, doubt, a first sync, or a day-old last full download
     * fall back to the full download, which waits for an unmetered network
     * when [isMetered] and a cached shelf exists. See [planLibrarySync].
     */
    internal suspend fun refreshLibraryItemsIfChanged(libraryId: String, isMetered: Boolean): LibraryRefreshOutcome {
        val identity = watermarkStore.currentSyncIdentity()
        val flightKey = identity.flightKey(libraryId)
        return syncFlights.run(flightKey, LibrarySyncKind.CHECK) {
            lockFor(flightKey).withLock { runCheckLocked(libraryId, identity, isMetered) }
        }
    }

    private suspend fun runCheckLocked(
        libraryId: String,
        identity: LibrarySyncIdentity,
        isMetered: Boolean,
    ): LibraryRefreshOutcome {
        if (!isCurrent(identity)) return LibraryRefreshOutcome.CheckFailed(SYNC_ACCOUNT_CHANGED)
        val key = identity.account
        val watermark = watermarkStore.get(key, libraryId)
        val localCount = audioBookDao.countServerBooksByLibrary(libraryId)
        val hasCachedRows = localCount > 0
        val change = if (watermark == null) {
            LibraryChange.Full("no earlier sync of this library")
        } else {
            when (val head = apiService.getLibraryHead(libraryId)) {
                is RemoteResult.Ok -> decideLibraryChange(watermark, head.value, localCount)
                is RemoteResult.Partial -> return LibraryRefreshOutcome.CheckFailed(head.reason)
                is RemoteResult.Failed -> return LibraryRefreshOutcome.CheckFailed(head.reason)
            }
        }
        val plan = planLibrarySync(
            change = change,
            isStale = watermark?.let { isWatermarkStale(it, System.currentTimeMillis()) } ?: false,
            isMetered = isMetered,
            hasCachedRows = hasCachedRows,
        )
        val reason = (change as? LibraryChange.Full)?.reason ?: "last full download is over a day old"
        return when (plan) {
            LibrarySyncPlan.SKIP -> LibraryRefreshOutcome.Unchanged(watermark?.itemCount ?: localCount)
            LibrarySyncPlan.DEFER_FULL -> deferUntilUnmetered(libraryId, reason)
            LibrarySyncPlan.FULL -> runBackgroundFullLocked(libraryId, identity, reason)
            LibrarySyncPlan.INCREMENTAL ->
                runIncrementalLocked(libraryId, identity, watermark!!, isMetered, hasCachedRows)
        }
    }

    private suspend fun runIncrementalLocked(
        libraryId: String,
        identity: LibrarySyncIdentity,
        watermark: com.ninelivesaudio.app.domain.model.LibrarySyncWatermark,
        isMetered: Boolean,
        hasCachedRows: Boolean,
    ): LibraryRefreshOutcome {
        val fetched = apiService.getLibraryItemsAddedSince(
            libraryId = libraryId,
            cutoff = incrementalCutoff(watermark),
            pageSize = INCREMENTAL_PAGE_SIZE,
            maxPages = INCREMENTAL_MAX_PAGES,
        )
        val key = identity.account
        val value = (fetched as? RemoteResult.Ok)?.value
            ?: return fallBackToFullLocked(libraryId, identity, (fetched as? RemoteResult.Failed)?.reason ?: "added-since fetch failed", isMetered, hasCachedRows)
        val books = value.items
        val alreadyCached = fetchByIdChunks(books.map { it.id }) { ids ->
            audioBookDao.getServerIdsInLibrary(libraryId, ids)
        }.toHashSet()
        val newBookCount = newBooksSinceWatermark(watermark, books, alreadyCached)
        if (books.isNotEmpty()) {
            val merged = mergeForSave(books)
            // Added books are saved even when the counts disagree below: they
            // are real, and the full download that follows prunes, not this.
            // Never into a cache another account has taken over meanwhile:
            // the account check and the save are one step no sign-in splits.
            val entities = withContext(Dispatchers.Default) { merged.map { it.toEntity() } }
            val saved = withContext(NonCancellable) {
                watermarkStore.runIfCurrent(identity) { audioBookDao.upsertAll(entities) }
            }
            if (!saved) return LibraryRefreshOutcome.CheckFailed(SYNC_ACCOUNT_CHANGED)
            _booksSaved.tryEmit(libraryId)
        }
        if (newBookCount == null || !incrementalCountsAgree(watermark.itemCount, newBookCount, value.total)) {
            // The books just saved are not in the watermark's count, so it no
            // longer describes the cache. Forgetting it keeps every later
            // check on the full download until one completes.
            watermarkStore.remove(key, libraryId)
            return fallBackToFullLocked(
                libraryId,
                identity,
                if (newBookCount == null) {
                    "cannot tell which books near the newest one are new"
                } else {
                    "server has ${value.total} books, expected ${watermark.itemCount} + $newBookCount new"
                },
                isMetered,
                hasCachedRows,
            )
        }
        watermarkStore.put(watermarkAfterIncremental(watermark, books, value.total, System.currentTimeMillis()))
        return LibraryRefreshOutcome.Incremental(newBookCount = newBookCount, itemCount = value.total)
    }

    private suspend fun fallBackToFullLocked(
        libraryId: String,
        identity: LibrarySyncIdentity,
        reason: String,
        isMetered: Boolean,
        hasCachedRows: Boolean,
    ): LibraryRefreshOutcome =
        if (shouldDeferFullSync(isMetered, explicit = false, hasCachedRows)) {
            deferUntilUnmetered(libraryId, reason)
        } else {
            runBackgroundFullLocked(libraryId, identity, reason)
        }

    private suspend fun runBackgroundFullLocked(
        libraryId: String,
        identity: LibrarySyncIdentity,
        reason: String,
    ): LibraryRefreshOutcome {
        if (isFullSyncBackedOff(fullSyncFailures[libraryId], monotonicNowMs())) {
            return LibraryRefreshOutcome.Deferred("$reason (waiting after failed downloads)", untilUnmetered = false)
        }
        return runFullSyncLocked(libraryId, identity)
    }

    private fun deferUntilUnmetered(libraryId: String, reason: String): LibraryRefreshOutcome {
        _deferredFullSyncs.update { it + libraryId }
        return LibraryRefreshOutcome.Deferred(reason, untilUnmetered = true)
    }

    private suspend fun runFullSyncLocked(libraryId: String, identity: LibrarySyncIdentity): LibraryRefreshOutcome {
        // A check that got here is now a full download: let a full refresh
        // asked for meanwhile share it rather than queue a second one.
        syncFlights.upgradeToFull(identity.flightKey(libraryId))
        val key = identity.account
        val tally = FullSyncTally()
        val result = runLibraryItemSyncPass(
            libraryId = libraryId,
            fetchPages = { onPage -> apiService.streamLibraryItems(libraryId, onPage = onPage) },
            mergeItems = ::mergeForSave,
            upsertAll = ::saveMerged,
            cachedNonDownloadedIds = audioBookDao::getNonDownloadedServerIdsByLibrary,
            deleteByIds = audioBookDao::deleteServerBooksByIds,
            deleteAllServerBooks = { id -> audioBookDao.deleteServerBooksByLibrary(id) },
            onPageSaved = { books ->
                tally.add(books)
                _booksSaved.tryEmit(libraryId)
            },
            writeIfCurrent = { write -> watermarkStore.runIfCurrent(identity, write) },
        )
        // Another account signed in mid-download. Nothing more was written,
        // and this account's watermark and failure count stay as they were.
        if (result is RemoteResult.Failed && result.reason == SYNC_ACCOUNT_CHANGED) {
            return LibraryRefreshOutcome.Full(result)
        }
        val watermark = watermarkAfterFullSync(key, libraryId, tally, result, System.currentTimeMillis())
        if (watermark != null) watermarkStore.put(watermark) else watermarkStore.remove(key, libraryId)
        if (result is RemoteResult.Ok) {
            fullSyncFailures.remove(libraryId)
            _deferredFullSyncs.update { it - libraryId }
        } else {
            fullSyncFailures.compute(libraryId) { _, previous ->
                FullSyncFailures(count = (previous?.count ?: 0) + 1, lastFailureAtMs = monotonicNowMs())
            }
        }
        return LibraryRefreshOutcome.Full(result)
    }

    // Thousands of books are merged and JSON-encoded here, so keep it off the
    // main thread the Library calls from.
    private suspend fun mergeForSave(remote: List<AudioBook>): List<AudioBook> =
        withContext(Dispatchers.Default) {
            mergeSyncedBooksLean(
                remote,
                audioBookDao::getSyncMergeStates,
                audioBookDao::getByIds,
                playbackProgressDao::getByAudioBookIds,
            )
        }

    private suspend fun saveMerged(books: List<AudioBook>) {
        audioBookDao.upsertAll(withContext(Dispatchers.Default) { books.map { it.toEntity() } })
    }

    private fun monotonicNowMs(): Long = System.nanoTime() / 1_000_000L

    /**
     * Applies an authoritative library-list removal under the same lock as
     * that library's item sync. A list response cannot prune this library
     * while an older item response is still in flight and about to upsert its
     * stale books.
     */
    internal suspend fun pruneServerBooksForRemovedLibrary(libraryId: String): Boolean =
        runSerializedLibraryItemPrune(lockFor(watermarkStore.currentSyncIdentity().flightKey(libraryId))) {
            audioBookDao.deleteServerBooksByLibrary(libraryId)
            audioBookDao.hasDownloadedServerBooks(libraryId)
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

    /**
     * Import a scan's books and carry any moved book's place onto its new row,
     * as one database transaction. Together or not at all: a half-applied pass
     * (rows imported, position not yet moved) would look on the next scan like
     * the new row had always been there, and the carry-over would never be
     * retried while the old row got archived out from under it.
     */
    suspend fun importLocalBooksCarryingMoves(
        libraryId: String,
        books: List<AudioBook>,
        existingIds: List<String>,
        seenIds: List<String>,
    ) {
        database.withTransaction {
            importLocalBooks(libraryId, books)
            carryOverMovedLocalProgress(existingIds, seenIds, books)
        }
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
     * Best-effort rescue for a book that moved rather than disappeared
     * (issue #20). A local book's id hashes its path under the picked root, so
     * dragging a folder one level deeper archives the old row and imports a new
     * one, and the user's place stays with the row nobody can see any more.
     *
     * Call this AFTER the scan's books are imported and BEFORE the missing ones
     * are archived. [existingIds] is the library's LIVE local ids as they were
     * before the import, [seenIds] is what the scan vouched for. Anything in one
     * and not the other is a candidate; [matchMovedLocalBooks] only pairs the
     * unambiguous ones, so a scan with nothing obvious to carry does nothing.
     *
     * Live, not all: a book that was sitting in the archive and turns up at a
     * new path in this scan has arrived as far as the library is concerned, and
     * a move into a folder someone used before is an ordinary thing to do.
     * Counting archived rows as "already here" quietly skipped that case.
     */
    suspend fun carryOverMovedLocalProgress(
        existingIds: List<String>,
        seenIds: List<String>,
        arrived: List<AudioBook>,
    ) {
        val seen = seenIds.toSet()
        val vanishedIds = existingIds.filterNot { it in seen }
        if (vanishedIds.isEmpty()) return

        val existing = existingIds.toSet()
        val arrivedBooks = arrived.filterNot { it.id in existing }
        if (arrivedBooks.isEmpty()) return

        val vanishedBooks = fetchByIdChunks(vanishedIds, audioBookDao::getByIds).map { it.toDomain() }
        val moves = matchMovedLocalBooks(
            vanished = vanishedBooks.map { it.fingerprint() },
            arrived = arrivedBooks.map { it.fingerprint() },
        )
        if (moves.isEmpty()) return
        val vanishedById = vanishedBooks.associateBy { it.id }

        for ((from, to) in moves) {
            val progress = playbackProgressDao.getByAudioBookId(from) ?: continue
            // The destination row was just imported with a fresh zero position,
            // so there is nothing of the user's here to overwrite. The progress
            // fraction rides along from the old row: it is the same book with
            // the same runtime, and it is what the library card actually shows.
            playbackProgressDao.upsert(progress.copy(audioBookId = to))
            audioBookDao.updateLocalPosition(
                id = to,
                currentTimeSeconds = progress.positionSeconds,
                progress = vanishedById[from]?.progress ?: 0.0,
                isFinished = progress.isFinished,
            )
        }
    }

    private fun AudioBook.fingerprint() = LocalBookFingerprint(
        id = id,
        folderName = folderNameOfTrackUri(audioFiles.firstOrNull()?.localPath ?: localPath),
        // Sorted so the same book fingerprints the same whichever side of the
        // move it is read from, without collapsing repeated tracks the way a
        // set would.
        tracks = audioFiles
            .filter { it.filename.isNotBlank() }
            .map { it.filename to it.size }
            .sortedWith(compareBy({ it.first }, { it.second })),
    )

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

    /** Ids of the local books a library currently shows (archived excluded). */
    suspend fun getLiveLocalIds(libraryId: String): List<String> =
        audioBookDao.getLiveLocalIdsByLibrary(libraryId)

    /** Ids of the archived local books in a library. */
    suspend fun getArchivedLocalIds(libraryId: String): List<String> =
        audioBookDao.getArchivedLocalIdsByLibrary(libraryId)

    /** Delete all audiobooks from local DB. */
    suspend fun deleteAll() {
        audioBookDao.deleteAll()
    }

    // ─── Readers outside the Library shelf ───────────────────────────────

    /**
     * The books among [ids] that belong to one library and source, looked up
     * 500 ids at a time. Ids that are missing or belong elsewhere are left out.
     */
    suspend fun getByIdsInLibraryAndSource(
        libraryId: String,
        isLocal: Boolean,
        ids: Collection<String>,
    ): List<AudioBook> =
        fetchByIdChunks(ids.distinct()) { chunk ->
            audioBookDao.getByIdsInLibraryAndSource(libraryId, if (isLocal) 1 else 0, chunk)
        }.map { it.toDomain() }

    /** One page of Android Auto's Library list, A to Z, light rows. */
    suspend fun getAutoBrowsePage(libraryId: String, isLocal: Boolean, limit: Int, offset: Int): List<AutoBrowseRow> =
        audioBookDao.getAutoBrowsePage(libraryId, if (isLocal) 1 else 0, limit, offset)

    /** One page of Android Auto's Downloaded list, A to Z, light rows. */
    suspend fun getAutoDownloadedPage(libraryId: String, isLocal: Boolean, limit: Int, offset: Int): List<AutoBrowseRow> =
        audioBookDao.getAutoDownloadedPage(libraryId, if (isLocal) 1 else 0, limit, offset)

    /** One page of Android Auto search hits for an escaped LIKE [pattern], light rows. */
    suspend fun searchAutoPage(
        libraryId: String,
        isLocal: Boolean,
        pattern: String,
        limit: Int,
        offset: Int,
    ): List<AutoBrowseRow> =
        audioBookDao.searchAutoPage(libraryId, if (isLocal) 1 else 0, pattern, limit, offset)

    /** How many Android Auto search hits [pattern] has, counting no further than [cap]. */
    suspend fun countAutoSearch(libraryId: String, isLocal: Boolean, pattern: String, cap: Int): Int =
        audioBookDao.countAutoSearch(libraryId, if (isLocal) 1 else 0, pattern, cap)
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
 * completed fetch, extracted so the branching is unit-testable without Room
 * (issue #14, PR #30 review).
 *
 * A complete ([isComplete]) fetch is authoritative for [libraryId]: the
 * cache should end up matching it exactly, including down to nothing when
 * [merged] is empty (a previously populated library that genuinely emptied
 * out). An incomplete (Partial) fetch is never authoritative: it upserts
 * what it got (some of the shelf beats none of it) but never prunes,
 * because a page it couldn't reach is not proof those books are gone.
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
    pruneServerLibraryTo(
        keptIds = merged.mapTo(mutableSetOf()) { it.id },
        libraryId = libraryId,
        cachedNonDownloadedIds = cachedNonDownloadedIds,
        deleteByIds = deleteByIds,
        deleteAllServerBooks = deleteAllServerBooks,
    )
}

/**
 * The prune half of [reconcileServerLibrary], for a complete fetch whose
 * books were already saved page by page: every cached non-downloaded server
 * book of [libraryId] not in [keptIds] goes, and an empty [keptIds] empties
 * the library. Only ever called after a complete fetch.
 */
internal suspend fun pruneServerLibraryTo(
    keptIds: Set<String>,
    libraryId: String,
    cachedNonDownloadedIds: suspend (libraryId: String) -> List<String>,
    deleteByIds: suspend (libraryId: String, ids: List<String>) -> Unit,
    deleteAllServerBooks: suspend (libraryId: String) -> Unit,
) {
    if (keptIds.isEmpty()) {
        deleteAllServerBooks(libraryId)
    } else {
        val missingIds = cachedNonDownloadedIds(libraryId).filterNot { it in keptIds }
        for (ids in missingIds.chunked(MAXIMUM_SERVER_BOOK_DELETE_BIND_COUNT)) {
            deleteByIds(libraryId, ids)
        }
    }
}

/**
 * One full fetch-save-prune pass for a library, the caller holding its lock.
 *
 * Each page is merged and saved as it arrives ([fetchPages] hands pages to
 * the callback), so a big library fills the shelf progressively and never
 * sits in memory whole. Saves run NonCancellable so a page is saved entirely
 * or not at all. Only a complete (Ok) fetch prunes, against every id it
 * delivered, and the prune also runs NonCancellable: once the fetch has
 * proved what the server holds, the cache is brought in line even if the
 * caller is cancelled in between. A Partial or Failed fetch keeps the pages
 * it saved and prunes nothing.
 *
 * [writeIfCurrent] runs a write only while the account the pass runs for is
 * still signed in, checking and writing as one step that no sign-in can
 * split (see [LibrarySyncWatermarkStore.runIfCurrent]). Each save and the
 * prune go through it, and once it refuses the pass stops, writes nothing
 * more, and fails with [SYNC_ACCOUNT_CHANGED]: the cache now belongs to
 * someone else, whose own sync fills and prunes it.
 */
internal suspend fun runLibraryItemSyncPass(
    libraryId: String,
    fetchPages: suspend (onPage: suspend (List<AudioBook>) -> Unit) -> RemoteResult<Int>,
    mergeItems: suspend (List<AudioBook>) -> List<AudioBook>,
    upsertAll: suspend (List<AudioBook>) -> Unit,
    cachedNonDownloadedIds: suspend (libraryId: String) -> List<String>,
    deleteByIds: suspend (libraryId: String, ids: List<String>) -> Unit,
    deleteAllServerBooks: suspend (libraryId: String) -> Unit,
    onPageSaved: suspend (List<AudioBook>) -> Unit = {},
    writeIfCurrent: suspend (write: suspend () -> Unit) -> Boolean = { write -> write(); true },
): RemoteResult<Int> {
    val keptIds = HashSet<String>()
    val result = try {
        fetchPages { page ->
            val merged = mergeItems(page)
            val saved = withContext(NonCancellable) {
                writeIfCurrent { if (merged.isNotEmpty()) upsertAll(merged) }
            }
            // Thrown to stop the download: the rest is for an account that
            // is no longer signed in.
            if (!saved) throw SyncAccountChangedException()
            merged.mapTo(keptIds) { it.id }
            onPageSaved(merged)
        }
    } catch (_: SyncAccountChangedException) {
        return RemoteResult.Failed(SYNC_ACCOUNT_CHANGED)
    }
    // A streaming fetch turns a throw in onPage into a short result, so
    // look again rather than trusting the catch above alone.
    if (!writeIfCurrent {}) return RemoteResult.Failed(SYNC_ACCOUNT_CHANGED)
    if (result is RemoteResult.Ok) {
        // The read of cached ids and every delete run inside one gated
        // step, so a sign-in waits for the whole prune rather than slipping
        // in between the read and a delete.
        val pruned = withContext(NonCancellable) {
            writeIfCurrent {
                pruneServerLibraryTo(
                    keptIds = keptIds,
                    libraryId = libraryId,
                    cachedNonDownloadedIds = cachedNonDownloadedIds,
                    deleteByIds = deleteByIds,
                    deleteAllServerBooks = deleteAllServerBooks,
                )
            }
        }
        if (!pruned) return RemoteResult.Failed(SYNC_ACCOUNT_CHANGED)
    }
    return result
}

/** Why a sync stopped: the account it ran for is no longer the signed-in one. */
internal const val SYNC_ACCOUNT_CHANGED = "the signed-in account changed during the sync"

private class SyncAccountChangedException : Exception(SYNC_ACCOUNT_CHANGED)

/**
 * [runLibraryItemSyncPass] under [mutex]. Holding the lock across the whole
 * fetch makes a later caller fetch only after every earlier response has
 * been applied, so an old response cannot upsert books that a newer
 * complete response already pruned.
 */
internal suspend fun runSerializedLibraryItemSync(
    mutex: Mutex,
    libraryId: String,
    fetchPages: suspend (onPage: suspend (List<AudioBook>) -> Unit) -> RemoteResult<Int>,
    mergeItems: suspend (List<AudioBook>) -> List<AudioBook>,
    upsertAll: suspend (List<AudioBook>) -> Unit,
    cachedNonDownloadedIds: suspend (libraryId: String) -> List<String>,
    deleteByIds: suspend (libraryId: String, ids: List<String>) -> Unit,
    deleteAllServerBooks: suspend (libraryId: String) -> Unit,
): RemoteResult<Int> = mutex.withLock {
    runLibraryItemSyncPass(
        libraryId = libraryId,
        fetchPages = fetchPages,
        mergeItems = mergeItems,
        upsertAll = upsertAll,
        cachedNonDownloadedIds = cachedNonDownloadedIds,
        deleteByIds = deleteByIds,
        deleteAllServerBooks = deleteAllServerBooks,
    )
}

/** Runs an authoritative item prune in the same ordering domain as item sync. */
internal suspend fun <T> runSerializedLibraryItemPrune(
    mutex: Mutex,
    prune: suspend () -> T,
): T = mutex.withLock { prune() }

/**
 * [mergeSyncedBooks] reading only the columns the merge keeps. The whole row
 * (audio file and chapter lists) is read only for a downloaded book whose
 * server copy came back without tracks or chapters, the one case the merge
 * borrows them from the local row. A book new to the cache takes the
 * position the progress pull saved for it ([getProgressRows]), see
 * [withPulledProgress].
 */
internal suspend fun mergeSyncedBooksLean(
    remote: List<AudioBook>,
    getMergeStates: suspend (List<String>) -> List<SyncMergeState>,
    getFullRows: suspend (List<String>) -> List<AudioBookEntity>,
    getProgressRows: suspend (List<String>) -> List<PlaybackProgressEntity>,
): List<AudioBook> {
    if (remote.isEmpty()) return emptyList()
    val states = fetchByIdChunks(remote.map { it.id }, getMergeStates).associateBy { it.id }
    val needDetail = remote.filter { book ->
        states[book.id]?.isDownloaded == 1 && (book.audioFiles.isEmpty() || book.chapters.isEmpty())
    }.map { it.id }
    val details = if (needDetail.isEmpty()) emptyMap() else fetchByIdChunks(needDetail, getFullRows).associateBy { it.id }
    val newIds = remote.filter { states[it.id] == null }.map { it.id }
    val pulled = if (newIds.isEmpty()) emptyMap() else fetchByIdChunks(newIds, getProgressRows).associateBy { it.audioBookId }
    return remote.map { book ->
        val state = states[book.id]
        if (state == null) withPulledProgress(book, pulled[book.id]) else mergeSyncedBook(book, state, details[book.id])
    }
}

/**
 * A book new to the cache, with the position the progress pull already saved
 * for it. The library list carries no listening progress, and a pull fetches
 * only the few most recently listened unknown books one by one, so every
 * other book with progress used to land on the shelf at zero and unfinished
 * (wrong in In Progress, Completed, and hide finished) until a later pull.
 * The saved row wins only when it is ahead, as local progress does in
 * [mergeSyncedBook].
 */
internal fun withPulledProgress(remote: AudioBook, row: PlaybackProgressEntity?): AudioBook {
    if (row == null) return remote
    val finished = row.isFinished == 1
    val remoteTime = remote.currentTime.inWholeMilliseconds / 1000.0
    if (row.positionSeconds <= remoteTime && (!finished || remote.isFinished)) return remote
    return remote.copy(
        currentTime = row.positionSeconds.seconds,
        progress = shelfProgress(
            currentTime = row.positionSeconds,
            duration = remote.duration.inWholeMilliseconds / 1000.0,
            isFinished = finished,
            existingProgress = remote.progress,
        ),
        isFinished = finished || remote.isFinished,
    )
}

internal fun AudioBookEntity.toSyncMergeState(): SyncMergeState = SyncMergeState(
    id = id,
    isDownloaded = isDownloaded,
    localPath = localPath,
    localCoverPath = localCoverPath,
    currentTimeSeconds = currentTimeSeconds,
    progress = progress,
    isFinished = isFinished,
    archivedAt = archivedAt,
)

/**
 * Merge a server book with its local row during sync, preserving local-only
 * state the server does not know about: the download flag, the on-disk audio
 * path, the cover persisted at download time, and any local playback progress
 * that is ahead of the server. Pure, so it is unit-testable without the DB.
 */
internal fun mergeSyncedBook(remote: AudioBook, local: AudioBookEntity?): AudioBook =
    mergeSyncedBook(remote, local?.toSyncMergeState(), local)

/**
 * [mergeSyncedBook] from the kept columns alone. [detail] is the whole local
 * row, needed only to borrow tracks or chapters for a downloaded book whose
 * server copy has none. Without it those stay as the server sent them.
 */
internal fun mergeSyncedBook(remote: AudioBook, local: SyncMergeState?, detail: AudioBookEntity?): AudioBook {
    if (local == null) return remote

    val withDownload = if (local.isDownloaded == 1) {
        remote.copy(
            isDownloaded = true,
            localPath = local.localPath,
            localCoverPath = local.localCoverPath,
            audioFiles = remote.audioFiles.ifEmpty { detail?.toDomain()?.audioFiles.orEmpty() },
            chapters = remote.chapters.ifEmpty { detail?.toDomain()?.chapters.orEmpty() },
        )
    } else remote

    // Preserve local progress if it's ahead of the server (offline playback),
    // or if it is a completion the server copy does not carry. The library
    // list holds no listening progress, so its copy always reads unfinished
    // at zero, and a book the progress pull marked finished at position zero
    // (finished on the server without a saved place) has no time to be
    // ahead with. Comparing times alone wiped that completion on the next
    // refresh. Only the progress pull, which reads the server's own record,
    // may mark a book unfinished again.
    val localTime = local.currentTimeSeconds
    val remoteTime = withDownload.currentTime.inWholeMilliseconds / 1000.0
    val localDone = local.isFinished == 1 || local.progress >= 1.0
    val remoteDone = withDownload.isFinished || withDownload.progress >= 1.0
    val merged = if (localTime > remoteTime || (localDone && !remoteDone)) {
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
    append("SELECT ").append(SHELF_BOOK_COLUMNS).append(" FROM AudioBooks ab")
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

    // Search title, author, series and narrator through the folded search
    // column, so accents and case never cause a miss. The bound pattern is
    // folded and escaped (see buildLibrarySqlArgs), so "%" and "_" typed in
    // the search box match themselves.
    if (hasSearch) {
        append(" AND ab.SearchText LIKE ? ESCAPE '\\'")
    }

    // No ORDER BY: the Library sorts in Kotlin (sortBooks), so sorting here
    // too was paid twice. Android Auto reads its own paged queries.
}

/**
 * The arguments for [buildLibrarySql]: the library, then the folded, escaped
 * search pattern when there is a search.
 */
internal fun buildLibrarySqlArgs(libraryId: String, searchQuery: String): Array<Any> {
    if (searchQuery.isBlank()) return arrayOf(libraryId)
    return arrayOf(libraryId, searchTextLikePattern(searchQuery))
}
