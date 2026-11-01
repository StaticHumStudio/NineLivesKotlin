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

const val SIGNED_OUT_PLAYBACK_ERROR = "Your sign in has expired. Sign in again in Settings."
const val BOOK_GONE_PLAYBACK_ERROR = "This book is no longer on the server."
const val FOLDER_ACCESS_LOST_ERROR = "Folder access was lost. Reconnect it in Settings."

/**
 * Plain words for an ExoPlayer failure, chosen by cause. [httpStatus] is the
 * server's answer when there was one.
 */
fun playerErrorMessage(
    errorCode: Int,
    httpStatus: Int? = null,
    isLocalBook: Boolean = false,
): String = when (errorCode) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
        "Could not reach the server to play this book. Check the connection and try again."
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> when (httpStatus) {
        401, 403 -> SIGNED_OUT_PLAYBACK_ERROR
        404 -> BOOK_GONE_PLAYBACK_ERROR
        else -> GENERIC_PLAYBACK_ERROR
    }
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
        if (isLocalBook) FOLDER_ACCESS_LOST_ERROR
        else "An audio file is missing. Try downloading the book again."
    else -> GENERIC_PLAYBACK_ERROR
}

/**
 * The error event for a failed load, or null when there is nothing to say. A
 * load nobody asked for (the paused restore at app start) stays silent.
 */
fun loadErrorEvent(message: String?, reportErrors: Boolean): PlaybackEvent.Error? =
    if (reportErrors && message != null) PlaybackEvent.Error(message) else null

/**
 * Books whose download the user is removing. A player that loses its file in
 * that window is the app's own doing, so it says nothing. The mark outlives
 * the removal by [graceMillis] because the player reports a moment late.
 */
class RemovalSuppression(
    private val graceMillis: Long = 5_000,
    private val clock: () -> Long,
) {
    private val active = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val endedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun begin(bookId: String) {
        endedAt.remove(bookId)
        active.add(bookId)
    }

    fun end(bookId: String) {
        endedAt[bookId] = clock()
        active.remove(bookId)
    }

    fun suppresses(bookId: String): Boolean {
        if (bookId in active) return true
        val ended = endedAt[bookId] ?: return false
        if (clock() - ended < graceMillis) return true
        endedAt.remove(bookId)
        return false
    }
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

/**
 * A session restored at app start is quiet end to end: its load, and the
 * player failure that can follow while it prepares, say nothing until the user
 * presses Play or starts a load themselves.
 */
class RestoreQuiet {
    private val quiet = java.util.concurrent.atomic.AtomicBoolean(false)

    fun enterLoad(reportErrors: Boolean) = quiet.set(!reportErrors)

    fun userPlay() = quiet.set(false)

    fun reports(): Boolean = !quiet.get()
}

/** Removing a download only explains an error from the removed local files, never from streaming. */
fun removalSilencesError(guardSuppresses: Boolean, playingLocalFiles: Boolean): Boolean =
    guardSuppresses && playingLocalFiles
