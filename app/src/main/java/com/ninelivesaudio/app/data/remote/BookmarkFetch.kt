package com.ninelivesaudio.app.data.remote

import com.ninelivesaudio.app.domain.model.Bookmark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What the per-book bookmark route (GET /api/me/bookmarks/{itemId}, added in
 * Audiobookshelf 2.36.0) said. [Unsupported] means an older server answered
 * 404 or 405, or sent a body that is not the expected shape, so the caller
 * falls back to the bookmarks inside /api/me.
 */
internal sealed interface ItemBookmarksOutcome {
    data class Found(val bookmarks: List<Bookmark>) : ItemBookmarksOutcome
    data object Unsupported : ItemBookmarksOutcome
    data class Failed(val reason: String) : ItemBookmarksOutcome
}

/** How long the bookmarks pulled out of /api/me are reused across player loads. */
internal const val ME_BOOKMARKS_TTL_MS = 5 * 60_000L

/**
 * Bookmarks read from /api/me, kept for [ttlMs] so opening books on an older
 * server does not download the whole profile (every progress row the account
 * has) each time. One fetch at a time, so two loads back to back share it.
 * Keyed by [sessionKey] so a different server URL or account never sees the
 * last one's bookmarks. A failure is never cached.
 */
internal class MeBookmarksCache(
    private val ttlMs: Long = ME_BOOKMARKS_TTL_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    @Volatile private var invalidations = 0L
    private var cachedKey: String? = null
    private var cachedAtMs = 0L
    private var cached: List<Bookmark>? = null

    suspend fun get(
        sessionKey: String,
        fetch: suspend () -> RemoteResult<List<Bookmark>>,
    ): RemoteResult<List<Bookmark>> = mutex.withLock {
        val hit = cached
        if (hit != null && cachedKey == sessionKey && now() - cachedAtMs < ttlMs) {
            return@withLock RemoteResult.Ok(hit)
        }
        val startedAt = invalidations
        val result = fetch()
        // A bookmark added or removed while this fetch was in flight makes the
        // answer stale, so it is handed back once but not kept.
        if (result is RemoteResult.Ok && startedAt == invalidations) {
            cachedKey = sessionKey
            cachedAtMs = now()
            cached = result.value
        }
        result
    }

    /** Drops the cached list, after a bookmark is added or removed. */
    fun invalidate() {
        invalidations++
        cached = null
    }
}

/**
 * One book's bookmarks, from the per-book route when the server has it and
 * from the cached /api/me otherwise. Every failure comes back as
 * [RemoteResult.Failed], never as an empty list, so a network blip cannot
 * pass for "this book has no bookmarks".
 */
internal suspend fun loadItemBookmarks(
    itemId: String,
    sessionKey: String,
    cache: MeBookmarksCache,
    fetchItem: suspend (String) -> ItemBookmarksOutcome,
    fetchAll: suspend () -> RemoteResult<List<Bookmark>>,
): RemoteResult<List<Bookmark>> = remoteResultCatching {
    when (val outcome = fetchItem(itemId)) {
        is ItemBookmarksOutcome.Found -> RemoteResult.Ok(sortedForBook(itemId, outcome.bookmarks))
        is ItemBookmarksOutcome.Failed -> RemoteResult.Failed(outcome.reason)
        ItemBookmarksOutcome.Unsupported -> cache.get(sessionKey) {
            try {
                fetchAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RemoteResult.Failed(describeFailure(e))
            }
        }.map { sortedForBook(itemId, it) }
    }
}

private fun sortedForBook(itemId: String, bookmarks: List<Bookmark>): List<Bookmark> =
    bookmarks.filter { it.libraryItemId == itemId }.sortedBy { it.time }
