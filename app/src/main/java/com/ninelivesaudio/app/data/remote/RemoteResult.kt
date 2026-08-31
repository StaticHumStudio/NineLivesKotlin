package com.ninelivesaudio.app.data.remote

import kotlinx.coroutines.CancellationException

/**
 * A remote call that either produced a value or failed for a nameable reason.
 * Introduced because every library fetch used to collapse timeouts, HTTP
 * errors and parse failures into an empty list, which is indistinguishable
 * from a shelf that really is empty.
 */
sealed interface RemoteResult<out T> {
    data class Ok<T>(val value: T) : RemoteResult<T>
    data class Failed(val reason: String) : RemoteResult<Nothing>

    /**
     * The call produced something usable but did not finish, e.g. pagination
     * that got three pages in and then timed out. Kept distinct from Ok so a
     * shelf that silently stopped short is not reported as a complete shelf.
     */
    data class Partial<T>(val value: T, val reason: String) : RemoteResult<T>
}

/** A short, paste-into-an-email description of why a call failed. */
internal fun describeFailure(e: Exception): String {
    val type = e::class.simpleName ?: "Exception"
    val message = e.message?.takeIf { it.isNotBlank() } ?: return type
    val full = "$type: $message"
    return if (full.length <= MAX_FAILURE_REASON) full else full.take(MAX_FAILURE_REASON - 3) + "..."
}

/**
 * A fetch that stopped before it finished. Books already retrieved make it a
 * [RemoteResult.Partial]; nothing retrieved makes it a plain failure, because
 * Partial would imply the user has some of their shelf when they have none.
 */
internal fun <T> stoppedShort(fetched: List<T>, reason: String): RemoteResult<List<T>> =
    if (fetched.isEmpty()) RemoteResult.Failed(reason) else RemoteResult.Partial(fetched.toList(), reason)

/**
 * Decides Ok vs stopped-short for a paginated fetch that just terminated,
 * because a page coming back empty or shorter than the requested limit
 * (the loop's own signal to stop) is normally the last page — but only
 * proves the fetch is COMPLETE when [allItems] has actually reached
 * [total]. If it hasn't, the page was short for some other reason (a
 * server-side page cap, a transient inconsistency), and reporting Ok would
 * be a false completeness signal a cache-pruning caller could act on
 * (issue #14, PR #30 review, finding B). [total] of 0 means the server did
 * not report a total at all. It is complete only after the pagination loop
 * reached its own short or empty page terminator.
 */
internal fun <T> paginationResult(allItems: List<T>, total: Int, currentPage: Int): RemoteResult<List<T>> =
    if (total == 0 || allItems.size >= total) {
        RemoteResult.Ok(allItems.toList())
    } else {
        stoppedShort(allItems, "page $currentPage: got ${allItems.size} of $total reported")
    }

/** One page of a paginated fetch: either items (and the server's reported running total), or a reason the fetch stopped (HTTP failure, missing body). */
internal sealed class PageOutcome<T> {
    data class Page<T>(val results: List<T>, val total: Int) : PageOutcome<T>()
    data class Stopped<T>(val reason: String) : PageOutcome<T>()
}

/**
 * The pagination loop ApiService.getLibraryItems() (and any future paginated
 * fetch) runs, extracted so its termination logic is pinned directly against
 * a fake [fetchPage] instead of only being exercised through a live Retrofit
 * call. A page whose [PageOutcome.Page.results] come back empty, or shorter
 * than [limit], stops the loop — but [paginationResult] is what decides
 * whether that stop is a genuine Ok or a Partial/Failed shortfall against
 * the page's reported total.
 *
 * [onPageFailure] is a side-channel for the caller's own logging (e.g.
 * android.util.Log, which this function must stay free of to remain
 * unit-testable) — it does not affect the returned [RemoteResult].
 * Cancellation is rethrown, never converted to a result: a plain
 * `catch (Exception)` also catches CancellationException, which would turn
 * a deliberately cancelled sync into a persisted Partial/Failed instead of
 * letting structured concurrency unwind.
 */
internal suspend fun <T> runPaginatedFetch(
    limit: Int,
    onPageFailure: (page: Int, e: Exception) -> Unit = { _, _ -> },
    fetchPage: suspend (page: Int) -> PageOutcome<T>,
): RemoteResult<List<T>> {
    val allItems = mutableListOf<T>()
    var currentPage = 0
    return try {
        while (true) {
            when (val outcome = fetchPage(currentPage)) {
                is PageOutcome.Stopped -> return stoppedShort(allItems, outcome.reason)
                is PageOutcome.Page -> {
                    if (outcome.results.isEmpty()) return paginationResult(allItems, outcome.total, currentPage)
                    allItems.addAll(outcome.results)
                    if ((outcome.total > 0 && allItems.size >= outcome.total) || outcome.results.size < limit) {
                        return paginationResult(allItems, outcome.total, currentPage)
                    }
                    currentPage++
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable: the while(true) loop above only exits via return")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onPageFailure(currentPage, e)
        stoppedShort(allItems, "page $currentPage: ${describeFailure(e)}")
    }
}

private const val MAX_FAILURE_REASON = 120

/**
 * Runs [call] and turns a thrown failure into [RemoteResult.Failed].
 * Cancellation must escape uncaught: a plain `catch (e: Exception)` also
 * catches [CancellationException] (it is a RuntimeException subtype), which
 * turned a deliberately stopped sync into a persisted FAILED/PARTIAL result
 * and a failure banner instead of a silently cancelled request.
 */
internal suspend fun <T> remoteResultCatching(
    onFailure: (Exception) -> Unit = {},
    call: suspend () -> RemoteResult<T>,
): RemoteResult<T> =
    try {
        call()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onFailure(e)
        RemoteResult.Failed(describeFailure(e))
    }

internal fun <T, R> RemoteResult<T>.map(transform: (T) -> R): RemoteResult<R> = when (this) {
    is RemoteResult.Ok -> RemoteResult.Ok(transform(value))
    is RemoteResult.Partial -> RemoteResult.Partial(transform(value), reason)
    is RemoteResult.Failed -> this
}

/**
 * For callers that only want the data and already fall back to cache when it
 * is missing. A partial fetch yields what it got: some of the shelf beats a
 * blank screen.
 */
internal fun <T> RemoteResult<List<T>>.valueOrEmpty(): List<T> = when (this) {
    is RemoteResult.Ok -> value
    is RemoteResult.Partial -> value
    is RemoteResult.Failed -> emptyList()
}
