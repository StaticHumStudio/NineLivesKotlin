package com.ninelivesaudio.app.service

import androidx.media3.common.Player
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal enum class PlaybackTermination { STOP, COMPLETED, ERROR }

internal fun needsPlaybackPreparation(state: Int): Boolean =
    state == Player.STATE_IDLE || state == Player.STATE_ENDED

internal data class PlaybackResumePoint(val mediaItemIndex: Int, val positionMs: Long)

internal fun playbackResumePoint(state: Int, mediaItemIndex: Int, positionMs: Long): PlaybackResumePoint =
    if (state == Player.STATE_ENDED) PlaybackResumePoint(0, 0)
    else PlaybackResumePoint(mediaItemIndex, positionMs)

/**
 * Whether [position] is the end of a book [bookDuration] long: within its
 * last second, or past it. A stop there saves the book as finished, and a
 * load treats it as a finished book, so both read this one rule (#106). The
 * second of slack also covers the player's track sum running a little short
 * of the server's length.
 */
internal fun positionAtBookEnd(position: Duration, bookDuration: Duration): Boolean =
    bookDuration > Duration.ZERO &&
        position >= (bookDuration - BOOK_END_WINDOW).coerceAtLeast(Duration.ZERO)

private val BOOK_END_WINDOW = 1.seconds

internal fun finishedAtTermination(reason: PlaybackTermination, position: Duration, duration: Duration): Boolean =
    reason == PlaybackTermination.COMPLETED ||
        (reason == PlaybackTermination.STOP && positionAtBookEnd(position, duration))

// Loading a book finalizes whatever was playing before it. A stop() already ran
// that finalization, so re-running it here would flush the retained book a second
// time, under whatever mode is current by then.
internal fun shouldFinalizeRetainedBook(hasRetainedBook: Boolean, alreadyFinalized: Boolean): Boolean =
    hasRetainedBook && !alreadyFinalized

// Play on a retained book whose media items are gone must reload it. Preparing
// the empty playlist ends at once and the COMPLETED terminal writes the book
// as finished at its full duration.
internal fun shouldReloadRetainedBook(mediaItemCount: Int, hasRetainedBook: Boolean): Boolean =
    mediaItemCount == 0 && hasRetainedBook

internal enum class PlayAction { IGNORE_WHILE_LOADING, RELOAD_RETAINED, RETRY_AFTER_TERMINAL, RESUME }

/**
 * What a Play tap does. While a book loads, the screen still shows the
 * previous book but the player is being filled with the new one, so the tap
 * is ignored. A retained book with no media reloads, one whose player stopped
 * on an error or at the end waits for its terminal write and retries, and
 * anything else resumes.
 */
internal fun playAction(bookLoading: Boolean, hasRetainedBook: Boolean, mediaItemCount: Int, playerState: Int): PlayAction = when {
    bookLoading -> PlayAction.IGNORE_WHILE_LOADING
    shouldReloadRetainedBook(mediaItemCount, hasRetainedBook) -> PlayAction.RELOAD_RETAINED
    hasRetainedBook && needsPlaybackPreparation(playerState) -> PlayAction.RETRY_AFTER_TERMINAL
    else -> PlayAction.RESUME
}

/**
 * Whether a retry that waited on its book's terminal write may still prepare
 * the player. A load that started meanwhile owns the player even though the
 * previous book is still current, so the retry stands down.
 */
internal fun retainedRetryStillApplies(
    currentBookId: String?,
    retryBookId: String,
    samePlayer: Boolean,
    playerNeedsPreparation: Boolean,
    bookLoading: Boolean,
): Boolean = currentBookId == retryBookId && samePlayer && playerNeedsPreparation && !bookLoading

/**
 * The error retry from play() (#43). It waits for the book's terminal write,
 * then reclaims sync ownership, and only then re-arms the book's terminal
 * write and prepares the player. Re-arming before the wait let a stop or a
 * new load that landed during it write the book's end a second time.
 */
internal suspend fun runTerminalRetry(
    pending: PendingTerminalOwner,
    bookId: String,
    isCurrent: () -> Boolean,
    claimOwnership: suspend () -> Boolean,
    settle: suspend () -> Unit = {},
    rearm: () -> Unit,
    prepare: () -> Unit,
): Boolean {
    var prepared = false
    pending.runAfterPending(bookId, isCurrent) {
        if (claimOwnership()) {
            // Ask the server for the book's spot first, as a resume does
            // (#118). Another load or a stop may take the book meanwhile.
            settle()
            if (!isCurrent()) return@runAfterPending
            rearm()
            prepare()
            prepared = true
        }
    }
    return prepared
}

/**
 * Whether the loaded book's spot is new: the book played since a load or a
 * server spot placed it, or the user seeked (#118). Only then does a stop,
 * a playback error or a completion save it, queue it and send it. A stream
 * that never opened (a book deleted on the server, a 5xx, a timeout), or a
 * book that loaded and sat paused, has no listening, and saving its spot
 * would date the load's spot as new over newer progress on the server.
 */
internal class PlayedSpot {
    private val lock = Any()
    private var new = false

    // Moves on every change, so a save that started before one cannot
    // clear the mark after it.
    private var generation = 0L

    val isNew: Boolean get() = synchronized(lock) { new }

    private fun set(value: Boolean) = synchronized(lock) {
        new = value
        generation++
    }

    /** A load or a server spot moved the book. Nothing was listened there yet. */
    fun bookPlaced() = set(false)

    /** The book is playing: listening from here on is this phone's. */
    fun playbackStarted() = set(true)

    /** The user moved the book: where they put it is theirs to save. */
    fun userSeeked() = set(true)

    /** A pause save of the spot as it is now is starting. Pass the answer to [saveLanded]. */
    fun saveStarting(): Long = synchronized(lock) { generation }

    /**
     * The pause save landed: saved on the phone, and sent or queued for the
     * server. Unless the book played, was seeked or moved since it started,
     * a stop after it has nothing new to write. A save that never lands
     * (a stop cancelled it) leaves the stop to save.
     */
    fun saveLanded(token: Long) = synchronized(lock) {
        if (token == generation) new = false
    }

    /**
     * Whether a stop, an error or a completion writes the retained book's
     * progress: only once, and only for a new spot. A write makes the spot
     * saved, not new, so a retry that fails again sends nothing.
     */
    fun takeForTerminalWrite(hasRetainedBook: Boolean, alreadyFinalized: Boolean): Boolean = synchronized(lock) {
        val writes = shouldFinalizeRetainedBook(hasRetainedBook, alreadyFinalized) && new
        if (writes) {
            new = false
            generation++
        }
        writes
    }
}

/**
 * Whether a seek that reached the player was the user's: from the app, the
 * notification, a headset or Android Auto, in any state, even while the
 * first play buffers. The app moving the player itself ([appPositioning]: a
 * load, a retry, a resume, the resume rewind, a chapter refresh) is not, and
 * nothing a loading book's player does is.
 */
internal fun seekIsUsers(appPositioning: Boolean, bookLoading: Boolean, playbackState: PlaybackState): Boolean =
    !appPositioning && !bookLoading && playbackState != PlaybackState.LOADING

/**
 * Close a server session, after any of its listening already on the way to
 * the server lands. Closing first cut that listening off.
 */
internal suspend fun closeLeftoverSession(listening: ServerSessionListening?, closeRemote: suspend () -> Unit) {
    if (listening != null) listening.close(closeRemote) else closeRemote()
}
