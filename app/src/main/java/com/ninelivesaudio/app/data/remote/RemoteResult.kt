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
