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

internal fun finishedAtTermination(reason: PlaybackTermination, position: Duration, duration: Duration): Boolean =
    reason == PlaybackTermination.COMPLETED ||
        (reason == PlaybackTermination.STOP && duration > Duration.ZERO &&
            position >= (duration - 1.seconds).coerceAtLeast(Duration.ZERO))

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
    rearm: () -> Unit,
    prepare: () -> Unit,
): Boolean {
    var prepared = false
    pending.runAfterPending(bookId, isCurrent) {
        if (claimOwnership()) {
            rearm()
            prepare()
            prepared = true
        }
    }
    return prepared
}
