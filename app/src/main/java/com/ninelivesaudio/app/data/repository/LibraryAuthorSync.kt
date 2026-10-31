package com.ninelivesaudio.app.data.repository

import com.ninelivesaudio.app.data.remote.RemoteResult
import com.ninelivesaudio.app.domain.model.LibraryAuthor
import kotlinx.coroutines.CancellationException

/**
 * Whether a library's author list (issue #87) is fetched after [outcome].
 * After any sync that wrote books, yes. After a check that found nothing new,
 * or a download put off, only when this run has not stored the list yet, so a
 * library with no changes costs one request per app run, not one per check.
 * That idle fetch never happens on a metered network (the list carries bios
 * and can be large). A failed check or a failed download asks for nothing.
 */
internal fun shouldRefreshAuthors(
    outcome: LibraryRefreshOutcome,
    storedThisRun: Boolean,
    isMetered: Boolean,
): Boolean = when (outcome) {
    is LibraryRefreshOutcome.Full -> outcome.result !is RemoteResult.Failed
    is LibraryRefreshOutcome.Incremental -> true
    is LibraryRefreshOutcome.Unchanged, is LibraryRefreshOutcome.Deferred -> !storedThisRun && !isMetered
    is LibraryRefreshOutcome.CheckFailed -> false
}

/**
 * Fetches a library's author list and hands a whole one to [storeIfCurrent],
 * which writes it only while the account that asked is still signed in.
 * True only when a whole list was fetched and stored. A failed or partial
 * fetch stores nothing, so the last good list stays. Any error except
 * cancellation is a false, never a throw: this runs in the sync scope, which
 * has no handler, and an escaped exception would crash the app.
 */
internal suspend fun refreshLibraryAuthors(
    fetch: suspend () -> RemoteResult<List<LibraryAuthor>>,
    storeIfCurrent: suspend (List<LibraryAuthor>) -> Boolean,
): Boolean = try {
    when (val result = fetch()) {
        is RemoteResult.Ok -> storeIfCurrent(result.value)
        is RemoteResult.Partial, is RemoteResult.Failed -> false
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    false
}

/**
 * Remembers whether this run has stored [key]'s author list. A failed fetch
 * forgets the key, so a library that wrote books and then failed to fetch its
 * list is tried again on the next check instead of being skipped for the run.
 */
internal fun recordAuthorsStored(storedThisRun: MutableSet<String>, key: String, stored: Boolean) {
    if (stored) storedThisRun += key else storedThisRun -= key
}

/** Removes a pruned library's cached books and its author list together. */
internal suspend fun pruneLibraryRows(
    libraryId: String,
    deleteBooks: suspend (String) -> Unit,
    deleteAuthors: suspend (String) -> Unit,
) {
    deleteBooks(libraryId)
    deleteAuthors(libraryId)
}

/** Clear cache: server books, server libraries, and every library's author list. */
internal suspend fun clearServerLibraryCache(
    deleteBooks: suspend () -> Unit,
    deleteLibraries: suspend () -> Unit,
    deleteAuthors: suspend () -> Unit,
) {
    deleteBooks()
    deleteLibraries()
    deleteAuthors()
}
