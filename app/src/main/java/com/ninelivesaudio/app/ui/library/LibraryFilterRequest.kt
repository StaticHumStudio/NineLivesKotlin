package com.ninelivesaudio.app.ui.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Identifies one full filtered-shelf calculation for a selected library. */
internal data class LibraryFilterRequest(
    val libraryId: String,
    val generation: Long,
)

/**
 * Owns the one active filtered-shelf calculation. The generation check is kept
 * alongside cancellation because a data source is allowed to turn cancellation
 * into an ordinary result.
 */
internal class LibraryFilterPublication {
    private var generation = 0L
    @Volatile private var current: LibraryFilterRequest? = null
    private var job: Job? = null

    fun replace(libraryId: String): LibraryFilterRequest {
        job?.cancel()
        return LibraryFilterRequest(libraryId, ++generation).also { current = it }
    }

    fun invalidate() {
        job?.cancel()
        current = null
        generation++
    }

    fun isCurrent(request: LibraryFilterRequest): Boolean = current == request

    fun <T> launch(
        scope: CoroutineScope,
        request: LibraryFilterRequest,
        load: suspend () -> T,
        publish: (T) -> Unit,
    ): Job = scope.launch {
            val result = load()
            if (current == request) publish(result)
        }.also { job = it }
}
