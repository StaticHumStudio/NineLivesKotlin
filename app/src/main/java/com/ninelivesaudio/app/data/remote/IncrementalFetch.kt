package com.ninelivesaudio.app.data.remote

import kotlinx.coroutines.CancellationException

/**
 * The cheapest question a library can be asked: how many books it has and
 * which one was added last. One `limit=1&minified=1&sort=addedAt&desc=1`
 * request on Audiobookshelf. [newestItemId] and [newestAddedAt] are null when
 * the library is empty.
 */
internal data class LibraryHead(
    val total: Int,
    val newestItemId: String?,
    val newestAddedAt: Long?,
)

/** What an "added since" fetch brought back: the books and the server's book count. */
internal data class IncrementalFetch<T>(
    val items: List<T>,
    val total: Int,
)

/** What to do after one page of an "added since" fetch. */
internal sealed interface IncrementalPageStep {
    /** Every book on the page is newer than the cutoff and more may follow. */
    data object Continue : IncrementalPageStep

    /** The page reached the cutoff or the end of the list. */
    data object Done : IncrementalPageStep

    /**
     * The answer cannot be trusted or is not worth finishing. The caller
     * falls back to a full download instead of guessing.
     */
    data class GiveUp(val reason: String) : IncrementalPageStep
}

/**
 * Decides one page of a newest-first "added since" fetch.
 *
 * Audiobookshelf has no "updated since" sort, so the fetch pages through books
 * newest-added first and stops at the first book older than [cutoff]. The
 * order is checked rather than assumed: a server that ignored the sort would
 * hand back books in any order, and stopping at the first old one would then
 * miss new books further down. Equal neighbours are fine, because a book
 * added while paging pushes the next page down by one and repeats a row.
 *
 * [previousPageLast] is the last added time seen on the page before, so the
 * order is checked across the page boundary too. A book with no added time
 * gives up, as does running out of [maxPages] (that many new books is a job
 * for the full download).
 */
internal fun incrementalPageStep(
    addedAts: List<Long?>,
    previousPageLast: Long?,
    cutoff: Long,
    pageSize: Int,
    pageIndex: Int,
    maxPages: Int,
): IncrementalPageStep {
    if (addedAts.isEmpty()) return IncrementalPageStep.Done
    if (addedAts.any { it == null }) return IncrementalPageStep.GiveUp("a book came back without an added date")
    val times = addedAts.map { it!! }
    var last = previousPageLast
    for (time in times) {
        if (last != null && time > last) {
            return IncrementalPageStep.GiveUp("the server did not sort by added date")
        }
        last = time
    }
    if (times.last() < cutoff) return IncrementalPageStep.Done
    if (times.size < pageSize) return IncrementalPageStep.Done
    if (pageIndex + 1 >= maxPages) return IncrementalPageStep.GiveUp("more than $maxPages pages of new books")
    return IncrementalPageStep.Continue
}

/**
 * The paging loop for an "added since" fetch, kept free of Retrofit and
 * logging so its stopping rules are pinned by tests against a fake
 * [fetchPage]. Rows repeated across pages (a book added mid-fetch shifts the
 * offsets) are dropped by [itemKey]. A server whose reported total changes
 * between pages is mid-change, and the fetch gives up rather than reason
 * about a moving count.
 *
 * Every failure comes back as [RemoteResult.Failed]. There is no partial
 * answer here: the caller either trusts the whole thing or does a full
 * download. Cancellation is rethrown.
 */
internal suspend fun <T> runIncrementalFetch(
    pageSize: Int,
    maxPages: Int,
    cutoff: Long,
    addedAt: (T) -> Long?,
    itemKey: (T) -> String,
    onPageFailure: (page: Int, e: Exception) -> Unit = { _, _ -> },
    fetchPage: suspend (page: Int) -> PageOutcome<T>,
): RemoteResult<IncrementalFetch<T>> {
    require(maxPages > 0) { "maxPages must be positive" }
    val items = mutableListOf<T>()
    val seen = mutableSetOf<String>()
    var page = 0
    var total: Int? = null
    var previousLast: Long? = null
    return try {
        while (true) {
            val outcome = fetchPage(page)
            if (outcome is PageOutcome.Stopped) return RemoteResult.Failed(outcome.reason)
            outcome as PageOutcome.Page
            if (total != null && outcome.total != total) {
                return RemoteResult.Failed("page $page: book count moved from $total to ${outcome.total}")
            }
            total = outcome.total
            val addedAts = outcome.results.map(addedAt)
            val step = incrementalPageStep(
                addedAts = addedAts,
                previousPageLast = previousLast,
                cutoff = cutoff,
                pageSize = pageSize,
                pageIndex = page,
                maxPages = maxPages,
            )
            if (step is IncrementalPageStep.GiveUp) return RemoteResult.Failed("page $page: ${step.reason}")
            outcome.results.forEach { if (seen.add(itemKey(it))) items.add(it) }
            previousLast = addedAts.lastOrNull() ?: previousLast
            if (step == IncrementalPageStep.Done) return RemoteResult.Ok(IncrementalFetch(items.toList(), outcome.total))
            page++
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable: the loop above only exits via return")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onPageFailure(page, e)
        RemoteResult.Failed("page $page: ${describeFailure(e)}")
    }
}
