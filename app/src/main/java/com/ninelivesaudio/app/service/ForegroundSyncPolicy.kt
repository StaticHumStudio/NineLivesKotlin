package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.data.local.entity.BookProgressState
import com.ninelivesaudio.app.data.local.entity.PlaybackProgressEntity
import com.ninelivesaudio.app.domain.model.Library
import com.ninelivesaudio.app.domain.model.SyncResult
import com.ninelivesaudio.app.domain.model.UserProgress
import kotlin.math.abs

// The pure rules behind SyncManager's foreground timer, its check records,
// and its progress pull, pinned by ForegroundSyncPolicyTest.

/** A foreground entry inside this long after the last check does not check again. */
internal const val FOREGROUND_CHECK_DEBOUNCE_MS = 2 * 60_000L

/** How often a check repeats while the app stays in the foreground. */
internal const val FOREGROUND_CHECK_INTERVAL_MS = 15 * 60_000L

/**
 * How long to wait after the app comes to the foreground before its check.
 * No check yet, one at least [FOREGROUND_CHECK_DEBOUNCE_MS] old, or a clock
 * reading before the last one all mean "now". A check under two minutes old
 * (flipping to another app and straight back) waits out the rest of its
 * 15-minute interval instead.
 */
internal fun delayBeforeEntryCheck(lastCheckAtMs: Long?, nowMs: Long): Long {
    if (lastCheckAtMs == null) return 0L
    val elapsed = nowMs - lastCheckAtMs
    if (elapsed < 0 || elapsed >= FOREGROUND_CHECK_DEBOUNCE_MS) return 0L
    return (FOREGROUND_CHECK_INTERVAL_MS - elapsed).coerceAtLeast(0L)
}

/**
 * Whether a timer tick or a server return should run a check. Anything that
 * ran a sync in the last two minutes (Sync Now, sign-in, the entry check)
 * already answered the question.
 */
internal fun isCheckDue(lastCheckAtMs: Long?, nowMs: Long): Boolean {
    if (lastCheckAtMs == null) return true
    val elapsed = nowMs - lastCheckAtMs
    return elapsed < 0 || elapsed >= FOREGROUND_CHECK_DEBOUNCE_MS
}

/** What a background check did across every library, for deciding its record. */
internal data class CheckTally(
    val anyDeferred: Boolean = false,
    val wroteBooks: Boolean = false,
    val libraryListChanged: Boolean = false,
)

/**
 * Whether a background check writes a [com.ninelivesaudio.app.domain.model.LastSyncRecord].
 *
 * - A failure or a short fetch is written: that is the truth and the banner
 *   should say so.
 * - A deferred full download writes nothing more: the shelf is known to be
 *   behind, so a success would be a lie, and nothing failed.
 * - Books saved or the library list changing is written as the success it
 *   is, which also tells the Library to re-read its shelf.
 * - Nothing changed writes nothing, unless the last record was not a
 *   success (or there is none), in which case the clean check heals it.
 */
internal fun shouldPersistCheckReport(
    reportResult: SyncResult,
    tally: CheckTally,
    previousResult: SyncResult?,
): Boolean = when {
    reportResult != SyncResult.SUCCESS -> true
    tally.anyDeferred -> false
    tally.wroteBooks || tally.libraryListChanged -> true
    else -> previousResult != SyncResult.SUCCESS
}

/**
 * Whether the server's library list differs from the cached one in a way the
 * Library shows: a library added, removed, or renamed.
 */
internal fun libraryListChanged(cached: List<Library>, fetched: List<Library>): Boolean =
    cached.map { it.id to it.name }.toSet() != fetched.map { it.id to it.name }.toSet()

/**
 * The app plays audiobooks only. Podcast libraries need per-episode playback
 * the app does not have, so background syncs skip them instead of
 * downloading shelves nobody can play. Picking one in the Library still
 * loads it on demand.
 */
internal fun isSyncedLibrary(library: Library): Boolean = library.mediaType == "book"

/** How many books unknown to the cache a progress pull fetches one by one. */
internal const val MAX_UNKNOWN_BOOK_FETCHES = 12

/**
 * Which books a progress pull looks up one at a time because the cache has
 * no row for them yet: the most recently listened first, at most [cap].
 * A fresh install's first pull used to send one request per book with
 * progress, thousands in a row for a long-time listener, before the library
 * sync could start. The handful kept here is enough for Home's recently
 * played row, and the library sync brings the rest.
 */
internal fun unknownBooksToFetch(
    progress: List<UserProgress>,
    knownIds: Set<String>,
    cap: Int = MAX_UNKNOWN_BOOK_FETCHES,
): Set<String> = progress
    .asSequence()
    .filter { it.libraryItemId.isNotBlank() && it.libraryItemId !in knownIds }
    .sortedByDescending { it.lastUpdate ?: Long.MIN_VALUE }
    .map { it.libraryItemId }
    .distinct()
    .take(cap)
    .toSet()

private const val POSITION_EPSILON_SECONDS = 0.5
private const val PROGRESS_EPSILON = 1e-6

/**
 * Whether importing a server progress record would change nothing: the saved
 * position row and the book's shelf fields already say the same thing. Every
 * pull used to rewrite every book's whole row (audio file and chapter lists
 * included) inside a transaction, even when nothing moved.
 */
internal fun serverProgressIsAlreadyLocal(
    positionSeconds: Double,
    progress: Double,
    isFinished: Boolean,
    updatedAt: String?,
    localRow: PlaybackProgressEntity?,
    localBook: BookProgressState?,
): Boolean {
    if (localRow == null) return false
    val rowMatches = abs(localRow.positionSeconds - positionSeconds) < POSITION_EPSILON_SECONDS &&
        (localRow.isFinished == 1) == isFinished &&
        localRow.updatedAt == updatedAt
    if (!rowMatches) return false
    if (localBook == null) return true
    return abs(localBook.currentTimeSeconds - positionSeconds) < POSITION_EPSILON_SECONDS &&
        abs(localBook.progress - progress) < PROGRESS_EPSILON &&
        (localBook.isFinished == 1) == isFinished
}
