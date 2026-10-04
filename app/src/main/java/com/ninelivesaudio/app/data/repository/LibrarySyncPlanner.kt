package com.ninelivesaudio.app.data.repository

import com.ninelivesaudio.app.data.remote.LibraryHead
import com.ninelivesaudio.app.data.remote.RemoteResult
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.LibrarySyncWatermark
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.selects.select

// The pure decisions behind the library change check and the "added since"
// sync. Nothing here touches Room, Retrofit, or the clock, so every rule is
// pinned by unit tests (LibrarySyncPlannerTest, LibrarySyncSingleFlightTest).

/** How old the last full download may get before a check refreshes everything. */
internal const val FULL_REFRESH_MAX_AGE_MS = 24 * 60 * 60_000L

/**
 * How far before the stored newest book an "added since" fetch reaches back.
 * A server scan can stamp a book's added time a moment before another book
 * that commits first, so a sync can see the later one and not the earlier.
 * Re-fetching that sliver is idempotent. It is only an optimisation: a book
 * missed anyway shows up in the count check and triggers a full download.
 * Kept short because a bulk import adds hundreds of books a few seconds
 * apart, and every one inside the window is downloaded again on each check
 * that finds something new.
 */
internal const val INCREMENTAL_OVERLAP_MS = 60_000L

/**
 * Page size for the "added since" fetch. Smaller than the full download's 100
 * because a check that finds changes usually finds a handful of new books,
 * and each full-detail book is a few kilobytes on what may be a cell network.
 */
internal const val INCREMENTAL_PAGE_SIZE = 25

/** Past this many pages (500 books) the full download is the simpler answer. */
internal const val INCREMENTAL_MAX_PAGES = 20

/** How many libraries' watermarks are kept across every server and account. */
internal const val MAX_STORED_WATERMARKS = 64

/** A server and the account signed in to it. Watermarks never cross accounts. */
internal data class SyncAccountKey(val serverUrl: String, val username: String)

internal fun List<LibrarySyncWatermark>.watermarkFor(
    key: SyncAccountKey,
    libraryId: String,
): LibrarySyncWatermark? = firstOrNull {
    it.serverUrl == key.serverUrl && it.username == key.username && it.libraryId == libraryId
}

/** Replace this library's watermark, keeping only the most recently written [cap]. */
internal fun List<LibrarySyncWatermark>.withWatermark(
    watermark: LibrarySyncWatermark,
    cap: Int = MAX_STORED_WATERMARKS,
): List<LibrarySyncWatermark> {
    val others = filterNot {
        it.serverUrl == watermark.serverUrl && it.username == watermark.username && it.libraryId == watermark.libraryId
    }
    return (others + watermark).sortedByDescending { it.updatedAtMs }.take(cap)
}

internal fun List<LibrarySyncWatermark>.withoutWatermark(
    key: SyncAccountKey,
    libraryId: String,
): List<LibrarySyncWatermark> = filterNot {
    it.serverUrl == key.serverUrl && it.username == key.username && it.libraryId == libraryId
}

/** What the tiny head request says about a library since its last good sync. */
internal sealed interface LibraryChange {
    data object NoChange : LibraryChange

    /** Books were added and nothing suggests anything was removed. */
    data object Incremental : LibraryChange

    /** Only a full download can bring the shelf back in line. */
    data class Full(val reason: String) : LibraryChange
}

/**
 * Compares the server's head (book count and newest added book) with what
 * the last good sync recorded.
 *
 * - Same newest book and same count: nothing was added or removed.
 * - A newer newest book: books were added. The "added since" fetch then
 *   checks the count again to catch removals that happened alongside.
 * - Anything else (fewer books, the newest one gone, a count change with
 *   the same newest book, missing fields) can only be explained by a
 *   removal or a server oddity, and only a full download answers that.
 *
 * [localServerRowCount] is how many server books for this library are in the
 * cache. Fewer than the watermark recorded means the cache lost rows (a
 * server switch wipes them, for one) and the watermark no longer describes it.
 */
internal fun decideLibraryChange(
    watermark: LibrarySyncWatermark?,
    head: LibraryHead,
    localServerRowCount: Int,
): LibraryChange {
    if (watermark == null) return LibraryChange.Full("no earlier sync of this library")
    if (localServerRowCount < watermark.itemCount) {
        return LibraryChange.Full("the saved shelf has $localServerRowCount of ${watermark.itemCount} books")
    }
    val newestTime = head.newestAddedAt
    if (head.newestItemId == null || newestTime == null) {
        return if (head.total == 0 && head.newestItemId == null) {
            if (watermark.itemCount == 0) LibraryChange.NoChange else LibraryChange.Full("the library is now empty")
        } else {
            LibraryChange.Full("the newest book came back without an id or added date")
        }
    }
    val storedNewest = watermark.newestAddedAt ?: return LibraryChange.Incremental
    return when {
        newestTime < storedNewest -> LibraryChange.Full("the newest book was removed")
        newestTime == storedNewest && head.newestItemId == watermark.newestItemId ->
            if (head.total == watermark.itemCount) {
                LibraryChange.NoChange
            } else {
                LibraryChange.Full("book count moved from ${watermark.itemCount} to ${head.total} with no new book")
            }
        else -> LibraryChange.Incremental
    }
}

/**
 * Whether the last full download is old enough that a check should refresh
 * everything. This is the only way a metadata edit on the server (a title
 * fix, a new series) reaches a shelf nobody pulls to refresh, because the
 * server cannot be asked for edited books. A clock that moved backward also
 * counts as stale, so a bad clock can never freeze the shelf.
 */
internal fun isWatermarkStale(
    watermark: LibrarySyncWatermark,
    nowMs: Long,
    maxAgeMs: Long = FULL_REFRESH_MAX_AGE_MS,
): Boolean {
    val age = nowMs - watermark.lastFullSyncAtMs
    return age < 0 || age >= maxAgeMs
}

/**
 * A full download found necessary by a background check waits for an
 * unmetered network. One the user asked for (pull to refresh, Retry, Sync
 * Now, sign-in) never waits, and neither does one for a library with nothing
 * cached, because then the shelf would sit empty.
 */
internal fun shouldDeferFullSync(
    isMetered: Boolean,
    explicit: Boolean,
    hasCachedRows: Boolean,
): Boolean = isMetered && !explicit && hasCachedRows

internal enum class LibrarySyncPlan { SKIP, INCREMENTAL, FULL, DEFER_FULL }

/**
 * What a background check does about one library. A full download that is
 * due only because the last one is old ([isStale]) is not worth cell data,
 * so on a metered network it steps down to whatever the change check says.
 */
internal fun planLibrarySync(
    change: LibraryChange,
    isStale: Boolean,
    isMetered: Boolean,
    hasCachedRows: Boolean,
): LibrarySyncPlan = when (change) {
    is LibraryChange.Full ->
        if (shouldDeferFullSync(isMetered, explicit = false, hasCachedRows)) LibrarySyncPlan.DEFER_FULL else LibrarySyncPlan.FULL
    LibraryChange.Incremental ->
        if (isStale && !isMetered) LibrarySyncPlan.FULL else LibrarySyncPlan.INCREMENTAL
    LibraryChange.NoChange ->
        if (isStale && !isMetered) LibrarySyncPlan.FULL else LibrarySyncPlan.SKIP
}

/** The lower edge of an "added since" fetch. A library that was empty fetches everything. */
internal fun incrementalCutoff(watermark: LibrarySyncWatermark): Long =
    watermark.newestAddedAt?.let { it - INCREMENTAL_OVERLAP_MS } ?: Long.MIN_VALUE

/**
 * After applying the added books, the server's count must equal the old
 * count plus the books that are new to the cache. Any gap means books were
 * also removed (or moved away), which only a full download can prune.
 */
internal fun incrementalCountsAgree(
    previousCount: Int,
    newBookCount: Int,
    serverTotal: Int,
): Boolean = previousCount + newBookCount == serverTotal

/**
 * Running totals of a full download, kept page by page so the books
 * themselves never have to be held: how many distinct books, the newest by
 * added date, and whether any book came without an added date.
 */
internal class FullSyncTally {
    private val seen = HashSet<String>()
    var count: Int = 0
        private set
    var newestAddedAt: Long? = null
        private set
    var newestItemId: String? = null
        private set
    var missingAddedAt: Boolean = false
        private set

    fun add(books: List<AudioBook>) {
        for (book in books) {
            if (!seen.add(book.id)) continue
            count++
            val time = book.addedAt
            if (time == null) {
                missingAddedAt = true
            } else if (newestAddedAt == null || time > newestAddedAt!!) {
                newestAddedAt = time
                newestItemId = book.id
            }
        }
    }
}

/**
 * The watermark a complete full download leaves. Null when any book lacks an
 * added date: without it the next check could not compare, so the library
 * stays on full downloads instead of trusting a guess.
 */
internal fun watermarkAfterFullSync(
    key: SyncAccountKey,
    libraryId: String,
    tally: FullSyncTally,
    nowMs: Long,
): LibrarySyncWatermark? {
    if (tally.missingAddedAt) return null
    return LibrarySyncWatermark(
        serverUrl = key.serverUrl,
        username = key.username,
        libraryId = libraryId,
        newestAddedAt = tally.newestAddedAt,
        newestItemId = tally.newestItemId,
        itemCount = tally.count,
        lastFullSyncAtMs = nowMs,
        updatedAtMs = nowMs,
    )
}

/** The first wait after a background full download fails or stops short. */
internal const val FULL_SYNC_BACKOFF_BASE_MS = 15 * 60_000L

/** The longest wait between background full download attempts. */
internal const val FULL_SYNC_BACKOFF_MAX_MS = 4 * 60 * 60_000L

/** How a library's background full downloads have been failing lately. */
internal data class FullSyncFailures(val count: Int, val lastFailureAtMs: Long)

/** 15 minutes after the first failure, doubling each time, capped at 4 hours. */
internal fun fullSyncBackoffMs(failureCount: Int): Long {
    if (failureCount <= 0) return 0L
    val shift = (failureCount - 1).coerceAtMost(10)
    return (FULL_SYNC_BACKOFF_BASE_MS shl shift).coerceAtMost(FULL_SYNC_BACKOFF_MAX_MS)
}

/**
 * Whether a background full download should wait because recent ones kept
 * failing. A Partial run on a flaky server used to leave a non-success record,
 * and every flap of the server back to reachable restarted the whole
 * multi-minute download. Only background checks honour this. A refresh the
 * user asked for always runs. [nowMs] is a monotonic clock, and a reading
 * before the last failure counts as waited out.
 */
internal fun isFullSyncBackedOff(failures: FullSyncFailures?, nowMs: Long): Boolean {
    if (failures == null || failures.count <= 0) return false
    val waited = nowMs - failures.lastFailureAtMs
    return waited in 0 until fullSyncBackoffMs(failures.count)
}

/**
 * The watermark after an "added since" fetch whose counts agreed. The newest
 * book is the first one the server listed (it sorted newest first), and the
 * last full download time carries over unchanged.
 */
internal fun watermarkAfterIncremental(
    previous: LibrarySyncWatermark,
    fetched: List<AudioBook>,
    serverTotal: Int,
    nowMs: Long,
): LibrarySyncWatermark {
    val first = fetched.firstOrNull()
    val firstTime = first?.addedAt
    val advance = first != null && firstTime != null &&
        (previous.newestAddedAt == null || firstTime >= previous.newestAddedAt)
    return previous.copy(
        newestAddedAt = if (advance) firstTime else previous.newestAddedAt,
        newestItemId = if (advance) first!!.id else previous.newestItemId,
        itemCount = serverTotal,
        updatedAtMs = nowMs,
    )
}

/** What one library's sync or check actually did. */
internal sealed interface LibraryRefreshOutcome {
    /** The check found nothing new. Nothing was fetched or written. */
    data class Unchanged(val itemCount: Int) : LibraryRefreshOutcome

    /**
     * A full download is needed but waits: for an unmetered network when
     * [untilUnmetered], otherwise for the failure backoff to run out.
     */
    data class Deferred(val reason: String, val untilUnmetered: Boolean = true) : LibraryRefreshOutcome

    /** The head request itself failed, so nothing is known either way. */
    data class CheckFailed(val reason: String) : LibraryRefreshOutcome

    /** Added books were fetched and saved, and the counts agreed. */
    data class Incremental(val newBookCount: Int, val itemCount: Int) : LibraryRefreshOutcome

    /** The full download ran, as asked or as the fallback. [result] counts the books it saved. */
    data class Full(val result: RemoteResult<Int>) : LibraryRefreshOutcome
}

/**
 * The outcome as a book-count result for sync reports. Null for a deferral,
 * which is no verdict at all: the shelf is known to be behind, so it must
 * not be reported as current, and nothing failed either.
 */
internal fun LibraryRefreshOutcome.itemCountResult(): RemoteResult<Int>? = when (this) {
    is LibraryRefreshOutcome.Unchanged -> RemoteResult.Ok(itemCount)
    is LibraryRefreshOutcome.Deferred -> null
    is LibraryRefreshOutcome.CheckFailed -> RemoteResult.Failed(reason)
    is LibraryRefreshOutcome.Incremental -> RemoteResult.Ok(itemCount)
    is LibraryRefreshOutcome.Full -> result
}

/** Whether this outcome wrote books to the cache, so a shelf showing them should re-read. */
internal val LibraryRefreshOutcome.wroteBooks: Boolean
    get() = when (this) {
        is LibraryRefreshOutcome.Incremental -> true
        is LibraryRefreshOutcome.Full -> result !is RemoteResult.Failed
        else -> false
    }

/** A background check or a full download. */
internal enum class LibrarySyncKind { CHECK, FULL }

/**
 * Whether a new request can share the sync already running for the same
 * library. A full download answers anything. A check answers only another
 * check: someone who asked for a full refresh waits for the check to finish
 * and then gets their own full download.
 */
internal fun canJoinInFlight(inFlight: LibrarySyncKind, requested: LibrarySyncKind): Boolean =
    inFlight == LibrarySyncKind.FULL || requested == LibrarySyncKind.CHECK

/**
 * One sync per library at a time, shared by everyone who asks while it runs.
 *
 * Sign-in's sync and the Library's own load used to queue behind one mutex
 * and each download the whole library, back to back. Now a second request
 * that [canJoinInFlight] awaits the running one and gets the same result. A
 * request that cannot join waits for the running one to finish and then
 * starts its own, so the old ordering still holds: one fetch-and-apply after
 * another, never an older response landing after a newer one.
 *
 * The sync runs in [scope], not in the caller. A caller that is cancelled
 * (the Library leaving, a newer load replacing an older one) stops waiting,
 * but the download carries on for whoever else is waiting and still lands in
 * the cache, instead of throwing away minutes of work.
 */
internal class LibrarySyncSingleFlight<R>(
    private val scope: CoroutineScope,
) {
    private class Flight<R>(
        @Volatile var kind: LibrarySyncKind,
        val deferred: Deferred<R>,
        val upgraded: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private val lock = Any()
    private val flights = HashMap<String, Flight<R>>()

    suspend fun run(libraryId: String, kind: LibrarySyncKind, block: suspend () -> R): R {
        while (true) {
            var blocking: Flight<R>? = null
            val joined: Flight<R>? = synchronized(lock) {
                val running = flights[libraryId]
                when {
                    running != null && canJoinInFlight(running.kind, kind) -> running
                    running != null -> {
                        blocking = running
                        null
                    }
                    else -> startLocked(libraryId, kind, block)
                }
            }
            if (joined != null) return joined.deferred.await()
            // Not ours to share yet. Wait until it finishes or turns into a
            // full download (see [upgradeToFull]), then look again.
            val waitOn = blocking!!
            select {
                waitOn.deferred.onJoin {}
                waitOn.upgraded.onJoin {}
            }
            // Upgraded means this flight is (or was) the full download we
            // wanted, even if it already finished and left the map.
            if (canJoinInFlight(waitOn.kind, kind)) return waitOn.deferred.await()
        }
    }

    /**
     * Marks the running sync of [libraryId] as a full download. A check that
     * finds it needs one calls this, so a full refresh asked for meanwhile
     * (sign-in's sync while the Library's first check downloads a never
     * synced library) joins it instead of downloading everything again
     * right after.
     */
    fun upgradeToFull(libraryId: String) {
        synchronized(lock) {
            val running = flights[libraryId] ?: return
            if (running.kind != LibrarySyncKind.FULL) {
                running.kind = LibrarySyncKind.FULL
                running.upgraded.complete(Unit)
            }
        }
    }

    private fun startLocked(libraryId: String, kind: LibrarySyncKind, block: suspend () -> R): Flight<R> {
        lateinit var flight: Flight<R>
        val deferred = scope.async(start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                synchronized(lock) {
                    if (flights[libraryId] === flight) flights.remove(libraryId)
                }
            }
        }
        flight = Flight(kind, deferred)
        flights[libraryId] = flight
        deferred.start()
        return flight
    }
}
