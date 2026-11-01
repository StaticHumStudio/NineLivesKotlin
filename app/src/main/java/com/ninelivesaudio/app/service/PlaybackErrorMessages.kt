package com.ninelivesaudio.app.service

import androidx.media3.common.PlaybackException
import kotlinx.coroutines.CancellationException

const val GENERIC_PLAYBACK_ERROR = "That book would not play. Try again."

/** The text to show for a playback event, or null when the event is not a failure. */
fun PlaybackEvent.userMessage(): String? = when (this) {
    is PlaybackEvent.Error -> message.trim().ifEmpty { GENERIC_PLAYBACK_ERROR }
    else -> null
}

/**
 * Plain words for a failed load. A cancelled load (book switch, mode switch,
 * download removal, disconnect, closed screen) is the app's own doing, not a
 * failure, so it says nothing. Exception text is never shown to the user.
 */
fun loadFailureMessage(cause: Throwable): String? =
    if (cause is CancellationException) null else GENERIC_PLAYBACK_ERROR

/** Plain words for an ExoPlayer failure, chosen by cause. */
fun playerErrorMessage(errorCode: Int): String = when (errorCode) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
        "Could not reach the server to play this book. Check the connection and try again."
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
        "An audio file is missing. Try downloading the book again."
    else -> GENERIC_PLAYBACK_ERROR
}

/** Drops an identical message that arrives while the same one is still fresh. */
class PlaybackErrorGate(
    private val windowMillis: Long = 3_000,
    private val clock: () -> Long,
) {
    private var lastMessage: String? = null
    private var lastAt = 0L

    fun accept(message: String): Boolean {
        val now = clock()
        if (message == lastMessage && now - lastAt < windowMillis) return false
        lastMessage = message
        lastAt = now
        return true
    }
}
