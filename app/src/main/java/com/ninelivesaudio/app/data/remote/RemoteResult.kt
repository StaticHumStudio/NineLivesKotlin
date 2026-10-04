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
 * proves the fetch is COMPLETE when [allItems] has actually reached the
 * highest positive reported [total]. If it hasn't, the page was short for
 * some other reason (a server-side page cap or transient inconsistency), and
 * reporting Ok would
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
    data class Page<T>(
        val results: List<T>,
        val total: Int,
        /** Optional server pagination metadata, validated when supplied. */
        val reportedPage: Int? = null,
        val reportedPageCount: Int? = null,
    ) : PageOutcome<T>()
    /** [retryable] marks a stop worth another try (a 5xx, a rate limit), not a 4xx. */
    data class Stopped<T>(val reason: String, val retryable: Boolean = false) : PageOutcome<T>()
}

/**
 * The pagination loop ApiService.streamLibraryItems() (and any future paginated
 * fetch) runs, extracted so its termination logic is pinned directly against
 * a fake [fetchPage] instead of only being exercised through a live Retrofit
 * call. A page whose [PageOutcome.Page.results] come back empty, or shorter
 * than [limit], stops the loop — but [paginationResult] is what decides
 * whether that stop is a genuine Ok or a Partial/Failed shortfall against the
 * highest positive total reported by the run.
 *
 * [maxPages] bounds the run: reaching it returns a Partial (or Failed when
 * nothing was fetched) rather than an Ok, because a capped fetch has not
 * proved it saw the whole collection.
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
    maxPages: Int,
    onPageFailure: (page: Int, e: Exception) -> Unit = { _, _ -> },
    itemKey: ((T) -> String)? = null,
    fetchPage: suspend (page: Int) -> PageOutcome<T>,
): RemoteResult<List<T>> {
    val allItems = mutableListOf<T>()
    return when (
        val counted = runPaginatedFetchStreaming(
            limit = limit,
            maxPages = maxPages,
            onPageFailure = onPageFailure,
            itemKey = itemKey,
            onPage = { allItems.addAll(it) },
            fetchPage = fetchPage,
        )
    ) {
        is RemoteResult.Ok -> RemoteResult.Ok(allItems.toList())
        is RemoteResult.Partial -> RemoteResult.Partial(allItems.toList(), counted.reason)
        is RemoteResult.Failed -> counted
    }
}

/** [stoppedShort] by count, for a fetch that hands its pages on instead of keeping them. */
private fun stoppedShortCount(fetched: Int, reason: String): RemoteResult<Int> =
    if (fetched == 0) RemoteResult.Failed(reason) else RemoteResult.Partial(fetched, reason)

/** [paginationResult] by count. */
private fun paginationCountResult(fetched: Int, total: Int, currentPage: Int): RemoteResult<Int> =
    if (total == 0 || fetched >= total) {
        RemoteResult.Ok(fetched)
    } else {
        stoppedShortCount(fetched, "page $currentPage: got $fetched of $total reported")
    }

/**
 * [runPaginatedFetch] without holding the pages: each page's new rows go to
 * [onPage] as they arrive and only the count comes back. The termination
 * rules are the same code, so Ok still means the reported total was reached.
 * A big library's sync saves each page as it lands instead of keeping every
 * book in memory until the last page, and a page that throws in [onPage]
 * stops the fetch short exactly like a failed request.
 */
internal suspend fun <T> runPaginatedFetchStreaming(
    limit: Int,
    maxPages: Int,
    onPageFailure: (page: Int, e: Exception) -> Unit = { _, _ -> },
    itemKey: ((T) -> String)? = null,
    onPage: suspend (List<T>) -> Unit,
    fetchPage: suspend (page: Int) -> PageOutcome<T>,
): RemoteResult<Int> {
    require(maxPages > 0) { "maxPages must be positive" }
    var fetched = 0
    val seenKeys = mutableSetOf<String>()
    var currentPage = 0
    var highestReportedTotal = 0
    var expectedPageCount: Int? = null
    return try {
        while (true) {
            if (currentPage >= maxPages) {
                return stoppedShortCount(fetched, "page $currentPage: reached the $maxPages page cap")
            }
            when (val outcome = fetchPage(currentPage)) {
                is PageOutcome.Stopped -> return stoppedShortCount(fetched, outcome.reason)
                is PageOutcome.Page -> {
                    if (outcome.reportedPage != null && outcome.reportedPage != currentPage) {
                        return stoppedShortCount(
                            fetched,
                            "page $currentPage: server reported page ${outcome.reportedPage}",
                        )
                    }
                    // An empty page is checked below, not here. A real empty history
                    // reports numPages 0 (and the DTO defaults it to 0 for a server
                    // that omits the field), so guarding it as an impossible page
                    // count would turn "you have no history" into a failed fetch.
                    if (outcome.results.isNotEmpty() &&
                        outcome.reportedPageCount != null && outcome.reportedPageCount <= currentPage
                    ) {
                        return stoppedShortCount(
                            fetched,
                            "page $currentPage: invalid page count ${outcome.reportedPageCount}",
                        )
                    }
                    if (expectedPageCount != null && outcome.reportedPageCount != null &&
                        expectedPageCount != outcome.reportedPageCount
                    ) {
                        return stoppedShortCount(
                            fetched,
                            "page $currentPage: server changed page count from $expectedPageCount to ${outcome.reportedPageCount}",
                        )
                    }
                    if (outcome.reportedPageCount != null) expectedPageCount = outcome.reportedPageCount
                    highestReportedTotal = maxOf(highestReportedTotal, outcome.total)
                    if (outcome.results.isEmpty()) {
                        return paginationCountResult(fetched, highestReportedTotal, currentPage)
                    }
                    val newResults = itemKey?.let { key ->
                        outcome.results.filter { seenKeys.add(key(it)) }
                    } ?: outcome.results
                    if (newResults.isEmpty()) {
                        return stoppedShortCount(fetched, "page $currentPage: repeated rows made no progress")
                    }
                    onPage(newResults)
                    fetched += newResults.size
                    if (
                        (highestReportedTotal > 0 && fetched >= highestReportedTotal) ||
                        outcome.results.size < limit ||
                        (outcome.reportedPageCount != null && currentPage == outcome.reportedPageCount - 1)
                    ) {
                        return paginationCountResult(fetched, highestReportedTotal, currentPage)
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
        stoppedShortCount(fetched, "page $currentPage: ${describeFailure(e)}")
    }
}

/** How long a failed library page waits before each retry. */
internal val PAGE_RETRY_DELAYS_MS = listOf(2_000L, 6_000L)

/**
 * Fetches one page, trying again after each of [delaysMs] when it fails in a
 * way worth retrying: a thrown network error, or a [PageOutcome.Stopped]
 * marked retryable (a 5xx or a rate limit). A 401 or 404 is returned at
 * once. One slow page on a slow home server used to end the whole download
 * short with no second try. The last failure is returned (or rethrown) as
 * is, so the paging loop reports it exactly as before. Cancellation is
 * never retried.
 */
internal suspend fun <T> fetchPageWithRetry(
    delaysMs: List<Long> = PAGE_RETRY_DELAYS_MS,
    sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    fetch: suspend () -> PageOutcome<T>,
): PageOutcome<T> {
    var attempt = 0
    while (true) {
        val outcome = try {
            fetch()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (attempt >= delaysMs.size) throw e
            null
        }
        if (outcome != null) {
            val retry = outcome is PageOutcome.Stopped && outcome.retryable && attempt < delaysMs.size
            if (!retry) return outcome
        }
        sleep(delaysMs[attempt])
        attempt++
    }
}

/** HTTP statuses worth another try: server errors, timeouts, and rate limits. */
internal fun isRetryableHttpStatus(code: Int): Boolean = code >= 500 || code == 408 || code == 429

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
