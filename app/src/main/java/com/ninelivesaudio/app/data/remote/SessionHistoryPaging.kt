package com.ninelivesaudio.app.data.remote

import com.ninelivesaudio.app.data.remote.dto.ApiListeningSession
import com.ninelivesaudio.app.domain.model.ListeningSession
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.time.Duration.Companion.seconds

/**
 * A runaway guard, not a product limit: 1,000 pages of 50 is 50,000 sessions.
 * History pages until it covers the period it was asked for (issue #65), so a
 * real listener stops long before this. Reaching it still ends as Partial, so
 * the screen and the share card say the history is incomplete.
 */
internal const val SESSION_HISTORY_PAGE_CAP = 1_000

/** How far before a book's added date its history is read, for clock skew. */
internal const val BOOK_HISTORY_MARGIN_MS = 86_400_000L

/**
 * Whether [page] reaches past [coveredFrom], so no later page can hold a
 * session that started at or after it.
 *
 * Audiobookshelf lists `/api/me/listening-sessions` newest first by
 * `updatedAt` (`getUserListeningSessionsHelper` in the server's
 * `ApiRouter.js` sorts `b.updatedAt - a.updatedAt`), and a session's
 * `startedAt` is never after its `updatedAt`. So once a page's last row was
 * last updated before the period start, every row after it also started
 * before it. The later of the two timestamps is read, which is also right
 * for a server that sorts by `startedAt`.
 *
 * A row with no timestamps at all says nothing about where the page sits, so
 * the last row that has one decides. A page with none keeps the paging going.
 */
internal fun sessionPageReaches(page: List<ListeningSession>, coveredFrom: Long): Boolean {
    val lastKnown = page.asReversed()
        .map { maxOf(it.startedAt, it.updatedAt) }
        .firstOrNull { it > 0L }
        ?: return false
    return lastKnown < coveredFrom
}

/**
 * Pages listening history until it covers every session that started at or
 * after [coveredFrom], or to the end of the history when [coveredFrom] is
 * null. Older sessions on the last page come back too, so callers still
 * filter to their own window. Rows are sorted newest first by start time.
 */
internal suspend fun fetchSessionHistory(
    pageSize: Int,
    maxPages: Int,
    coveredFrom: Long?,
    onPageFailure: (page: Int, e: Exception) -> Unit = { _, _ -> },
    fetchPage: suspend (page: Int) -> PageOutcome<ListeningSession>,
): RemoteResult<List<ListeningSession>> =
    runPaginatedFetch(
        limit = pageSize,
        maxPages = maxPages,
        onPageFailure = onPageFailure,
        itemKey = { it.id },
        coveredBy = coveredFrom?.let { from -> { page -> sessionPageReaches(page, from) } },
    ) { page ->
        // A long history is many requests. A period switch cancels the load,
        // and this stops it before the next one goes out.
        currentCoroutineContext().ensureActive()
        fetchPage(page)
    }.map { sessions -> sessions.sortedByDescending(ListeningSession::startedAt) }

/**
 * Where a book's history starts. A session cannot predate its book on the
 * server, so a day before the book was added covers all of it. Null (the
 * whole history) when the added date is missing or unusable.
 */
internal fun bookHistoryStart(addedAt: Long?): Long? =
    addedAt?.minus(BOOK_HISTORY_MARGIN_MS)?.takeIf { it > 0L }

/**
 * One server session as the app keeps it. The session's own library id comes
 * along so its history still counts after its book leaves the cache (#66).
 */
internal fun ApiListeningSession.toListeningSession(): ListeningSession = ListeningSession(
    id = id,
    libraryItemId = libraryItemId,
    currentTime = currentTime.seconds,
    timeListening = timeListening.seconds,
    startedAt = normalizeEpochMillis(startedAt),
    updatedAt = normalizeEpochMillis(updatedAt),
    displayTitle = displayTitle,
    libraryId = libraryId?.takeIf { it.isNotBlank() },
)

/** Normalize an epoch value that might be seconds or milliseconds to milliseconds. */
internal fun normalizeEpochMillis(value: Long): Long =
    if (value in 1..999_999_999_999L) value * 1000 else value
