package com.ninelivesaudio.app.ui.player

import com.ninelivesaudio.app.data.remote.RemoteResult
import com.ninelivesaudio.app.data.remote.describeFailure
import com.ninelivesaudio.app.domain.model.Bookmark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Identifies one player's bookmark load without retaining a book object. */
internal data class BookmarkLoadRequest(
    val itemId: String?,
    val generation: Long,
)

/**
 * Runs bookmark loads for the active player item. Cancellation stops normal
 * repository work, while the request identity prevents a cancellation-swallowing
 * repository from publishing an old result afterward.
 */
internal class BookmarkPublication {
    private var generation = 0L
    @Volatile private var current: BookmarkLoadRequest? = null
    private var job: Job? = null

    fun replace(itemId: String?): BookmarkLoadRequest {
        job?.cancel()
        return BookmarkLoadRequest(itemId, ++generation).also { current = it }
    }

    fun refreshCurrent(itemId: String): BookmarkLoadRequest? {
        if (current?.itemId != itemId) return null
        return replace(itemId)
    }

    fun launch(
        scope: CoroutineScope,
        request: BookmarkLoadRequest,
        load: suspend (String) -> RemoteResult<List<Bookmark>>,
        publish: (RemoteResult<List<Bookmark>>) -> Unit,
    ): Job? {
        val itemId = request.itemId ?: return null
        return scope.launch {
            val bookmarks = try {
                load(itemId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                RemoteResult.Failed(describeFailure(e))
            }
            if (current == request) publish(bookmarks)
        }.also { job = it }
    }
}

/** What the player shows after a bookmark load: the list, and whether it failed. */
internal data class BookmarkListState(
    val bookmarks: List<Bookmark>,
    val loadFailed: Boolean,
)

/**
 * A failed load keeps whatever list is on screen and raises the failure flag,
 * so a network blip reads as "didn't load" instead of "all your bookmarks are
 * gone".
 */
internal fun bookmarkListState(
    current: List<Bookmark>,
    result: RemoteResult<List<Bookmark>>,
): BookmarkListState = when (result) {
    is RemoteResult.Ok -> BookmarkListState(result.value, loadFailed = false)
    is RemoteResult.Partial -> BookmarkListState(result.value, loadFailed = false)
    is RemoteResult.Failed -> BookmarkListState(current, loadFailed = true)
}
