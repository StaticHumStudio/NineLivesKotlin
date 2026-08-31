package com.ninelivesaudio.app.data.repository

import com.ninelivesaudio.app.data.local.converter.toDomain
import com.ninelivesaudio.app.data.local.converter.toEntity
import com.ninelivesaudio.app.data.local.dao.AudioBookDao
import com.ninelivesaudio.app.data.local.dao.LibraryDao
import com.ninelivesaudio.app.data.remote.ApiService
import com.ninelivesaudio.app.data.remote.RemoteResult
import com.ninelivesaudio.app.domain.model.Library
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibraryRepository @Inject constructor(
    private val libraryDao: LibraryDao,
    private val audioBookDao: AudioBookDao,
    private val audioBookRepository: AudioBookRepository,
    private val apiService: ApiService,
) {
    // Serializes syncFromServer(): SyncManager's account-wide sync and
    // LibraryViewModel's own screen-triggered refresh both call it
    // independently with no other coordination. Without this, overlapping
    // calls could apply their complete responses out of order -- an older,
    // slower fetch finishing AFTER a newer one and upserting back a library
    // the newer fetch had just correctly pruned (issue #14, PR #30 review,
    // finding B). Scoped to just this one /libraries call plus its DB
    // reconcile, not SyncManager's whole multi-library item sync, and this
    // repository never calls back into SyncManager, so holding it cannot
    // deadlock SyncManager's own syncMutex.
    private val syncFromServerMutex = Mutex()
    /** Observe all libraries from local DB (reactive). */
    fun observeAll(): Flow<List<Library>> =
        libraryDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    /** Observe Audiobookshelf libraries from local DB (reactive). */
    fun observeAudiobookshelf(): Flow<List<Library>> =
        libraryDao.observeAudiobookshelf().map { entities -> entities.map { it.toDomain() } }

    /** Observe Local Library roots from local DB (reactive). */
    fun observeLocalLibraries(): Flow<List<Library>> =
        libraryDao.observeLocal().map { entities -> entities.map { it.toDomain() } }

    /** Get all libraries from local DB (one-shot). */
    suspend fun getAll(): List<Library> =
        libraryDao.getAll().map { it.toDomain() }

    /** Get Audiobookshelf libraries from local DB (one-shot). */
    suspend fun getAudiobookshelf(): List<Library> =
        libraryDao.getAudiobookshelf().map { it.toDomain() }

    /** Get Local Library roots from local DB (one-shot). */
    suspend fun getLocalLibraries(): List<Library> =
        libraryDao.getLocal().map { it.toDomain() }

    /** Get a single library by ID. */
    suspend fun getById(id: String): Library? =
        libraryDao.getById(id)?.toDomain()

    /** Create or return a stable Local Library row for a persisted SAF folder URI. */
    suspend fun createLocalLibrary(name: String, folderUri: String): Library {
        val existing = libraryDao.getLocalByFolderUri(folderUri)
        if (existing != null) return existing.toDomain()

        val library = Library(
            id = stableLocalLibraryId(folderUri),
            name = name.ifBlank { "Local Library" },
            isLocal = true,
            folderUri = folderUri,
            mediaType = "book",
        )
        libraryDao.upsert(library.toEntity())
        return library
    }

    /**
     * Fetch libraries from server and save to local DB.
     *
     * A complete (Ok) fetch is authoritative — the cache is reconciled to
     * match it exactly, including pruning a library (and its cached books)
     * that the server no longer reports, down to nothing when the account
     * genuinely has zero libraries. A Partial fetch never prunes: it saves
     * what it got, but a page it couldn't reach is not proof a library is
     * gone (issue #14, PR #30 review, finding A — mirrors
     * AudioBookRepository.syncLibraryItems's reconcileServerLibrary one
     * level up). See [reconcileServerLibraries].
     *
     * The fetch and its reconcile are serialized behind [syncFromServerMutex]
     * (issue #14, PR #30 review, finding B) — see [runSerializedLibrarySync].
     */
    suspend fun syncFromServer(): RemoteResult<List<Library>> = runSerializedLibrarySync(
        mutex = syncFromServerMutex,
        fetchLibraries = apiService::getLibraries,
        cachedServerLibraryIds = { libraryDao.getAudiobookshelf().map { it.id } },
        upsertAll = { libraries -> libraryDao.upsertAll(libraries.map { it.toEntity() }) },
        deleteMissing = { keptIds -> libraryDao.deleteMissingAudiobookshelf(keptIds) },
        deleteAllServerLibraries = { libraryDao.deleteAudiobookshelf() },
        pruneLibraryBooks = audioBookRepository::pruneServerBooksForRemovedLibrary,
    )

    /** Save a single library to local DB. */
    suspend fun save(library: Library) {
        libraryDao.upsert(library.toEntity())
    }

    /** Save multiple libraries to local DB. */
    suspend fun saveAll(libraries: List<Library>) {
        libraryDao.upsertAll(libraries.map { it.toEntity() })
    }

    /** Delete all libraries from local DB. */
    suspend fun deleteAll() {
        libraryDao.deleteAll()
    }

    /** Delete a Local Library row by ID without affecting ABS libraries. */
    suspend fun removeLocalLibrary(id: String) {
        audioBookDao.deleteLocalByLibrary(id)
        libraryDao.deleteLocalById(id)
    }

    private fun stableLocalLibraryId(folderUri: String): String =
        "local_library_${sha256(folderUri)}"

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Reconciles the DAO's cached SERVER library rows against a completed
 * /libraries fetch, mirroring AudioBookRepository.reconcileServerLibrary's
 * semantics one level up (issue #14, PR #30 review, finding A).
 *
 * A complete ([isComplete]) fetch is authoritative — the cache should end
 * up matching [fetched] exactly, including down to nothing when [fetched]
 * is empty (the account genuinely has zero libraries now). An incomplete
 * (Partial) fetch is never authoritative: it upserts what it got (some of
 * the library list beats none of it) but never prunes, because a page it
 * couldn't reach is not proof those libraries are gone — that retention is
 * the same as before this fix.
 *
 * Local libraries are never touched: [cachedServerLibraryIds],
 * [upsertAll], [deleteMissing], and [deleteAllServerLibraries] are all
 * expected to scope to server (non-local) rows only — see
 * LibraryDao.getAudiobookshelf / upsertAll / deleteMissingAudiobookshelf /
 * deleteAudiobookshelf. A pruned library's cached books are pruned too via
 * [pruneLibraryBooks] removes non-downloaded books from omitted libraries,
 * then reports whether a downloaded server book survived. A library with a
 * surviving download stays selectable, so Home and Android Auto can still
 * reach it.
 */
internal suspend fun reconcileServerLibraries(
    isComplete: Boolean,
    fetched: List<Library>,
    cachedServerLibraryIds: suspend () -> List<String>,
    upsertAll: suspend (List<Library>) -> Unit,
    deleteMissing: suspend (keptIds: List<String>) -> Unit,
    deleteAllServerLibraries: suspend () -> Unit,
    pruneLibraryBooks: suspend (libraryId: String) -> Boolean,
) {
    if (fetched.isNotEmpty()) {
        upsertAll(fetched)
    }
    if (!isComplete) return

    val keptIds = fetched.map { it.id }.toSet()
    val omittedIds = cachedServerLibraryIds().filterNot { it in keptIds }

    val retainedOmittedIds = omittedIds.filter { libraryId -> pruneLibraryBooks(libraryId) }
    val keptIdsIncludingDownloads = fetched.map { it.id } + retainedOmittedIds

    if (keptIdsIncludingDownloads.isEmpty()) {
        deleteAllServerLibraries()
    } else {
        deleteMissing(keptIdsIncludingDownloads)
    }
}

/**
 * The full syncFromServer() operation — fetch, then [reconcileServerLibraries]
 * if warranted — held for its entire duration behind [mutex] (issue #14, PR
 * #30 review, finding B).
 *
 * SyncManager's account-wide sync and LibraryViewModel's own screen-triggered
 * refresh both call syncFromServer() independently, with nothing else
 * coordinating them. Without serialization, an older call's fetch could
 * still be in flight when a newer call starts, finishes, and reconciles a
 * fresher snapshot — the older call then finishing afterward would apply
 * its now-stale response and could upsert back a library the newer call had
 * just correctly pruned.
 *
 * Wrapping the FETCH itself in the mutex (not just the reconcile) is what
 * actually closes this: a caller can only start its own network call once
 * every earlier call's fetch-and-reconcile has completely finished, so
 * whichever fetch genuinely happens later is guaranteed to reflect a
 * same-or-newer look at the server — there is no ordering ambiguity left to
 * get wrong. Guarding only the reconcile step would still let two fetches
 * race, leaving the same stale-overwrites-fresh hazard.
 */
internal suspend fun runSerializedLibrarySync(
    mutex: Mutex,
    fetchLibraries: suspend () -> RemoteResult<List<Library>>,
    cachedServerLibraryIds: suspend () -> List<String>,
    upsertAll: suspend (List<Library>) -> Unit,
    deleteMissing: suspend (keptIds: List<String>) -> Unit,
    deleteAllServerLibraries: suspend () -> Unit,
    pruneLibraryBooks: suspend (libraryId: String) -> Boolean,
): RemoteResult<List<Library>> = mutex.withLock {
    val result = fetchLibraries()
    val fetched = when (result) {
        is RemoteResult.Ok -> result.value
        is RemoteResult.Partial -> result.value
        is RemoteResult.Failed -> return@withLock result
    }

    withContext(NonCancellable) {
        reconcileServerLibraries(
            isComplete = result is RemoteResult.Ok,
            fetched = fetched,
            cachedServerLibraryIds = cachedServerLibraryIds,
            upsertAll = upsertAll,
            deleteMissing = deleteMissing,
            deleteAllServerLibraries = deleteAllServerLibraries,
            pruneLibraryBooks = pruneLibraryBooks,
        )
    }

    result
}
