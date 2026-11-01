package com.ninelivesaudio.app.service.cover

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal val COVER_SOURCE_TIMEOUT = 8.seconds
internal val COVER_THUMB_TIMEOUT = 6.seconds

/**
 * One Search tap: every source in parallel, each under its own timeout, a failure or a
 * timeout dropping only that source. Then every thumbnail in parallel (needed to know
 * which covers are square), a failure dropping only that cover. Then source order, square first
 * within each source. A source whose page changed shape drops out with one warning.
 * Cancelling the caller cancels every request.
 */
internal suspend fun runCoverSearch(
    query: CoverQuery,
    sources: List<CoverSearchSource>,
    fetchThumbnail: suspend (CoverCandidate) -> CoverThumb?,
    sourceTimeout: Duration = COVER_SOURCE_TIMEOUT,
    thumbnailTimeout: Duration = COVER_THUMB_TIMEOUT,
    maxResults: Int = MAX_COVER_RESULTS,
): CoverSearchOutcome = coroutineScope {
    val outcomes = sources.map { source ->
        async {
            try {
                val found = guarded(rethrowUnparseable = true) { withTimeoutOrNull(sourceTimeout) { source.search(query) } }
                if (found == null) SourceOutcome.Failed(source.id) else SourceOutcome.Found(source.id, found)
            } catch (_: UnparseableCoverPageException) {
                // One warning, no search text: the page changed and this source is out until a fix ships.
                runCatching { android.util.Log.w("CoverSearch", "${source.id} answered a page this version cannot read, skipped") }
                SourceOutcome.Unparseable(source.id)
            }
        }
    }.awaitAll()
    val candidates = mergeCoverSources(outcomes, maxResults)
    val results = candidates.map { candidate ->
        async {
            guarded { withTimeoutOrNull(thumbnailTimeout) { fetchThumbnail(candidate) } }
                ?.let { CoverResult(candidate, it) }
        }
    }.awaitAll().filterNotNull()
    val ranked = rankCoverResults(results)
    CoverSearchOutcome(coverSearchPhase(outcomes, ranked), ranked)
}

/**
 * Null on any failure, but a real cancellation of the caller still cancels, and an
 * unparseable page is passed on when asked so the caller can tell it from a failure.
 */
private suspend fun <T> guarded(rethrowUnparseable: Boolean = false, block: suspend () -> T?): T? = try {
    block()
} catch (e: CancellationException) {
    currentCoroutineContext().ensureActive()
    null
} catch (e: UnparseableCoverPageException) {
    if (rethrowUnparseable) throw e
    null
} catch (_: Exception) {
    null
}
