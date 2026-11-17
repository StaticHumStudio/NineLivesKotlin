package com.ninelivesaudio.app.service

import androidx.media3.common.Player
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class PlaybackTerminationTest {
    @Test
    fun `completed multi track book replays from first track start`() {
        assertEquals(PlaybackResumePoint(0, 0), playbackResumePoint(Player.STATE_ENDED, 7, 40_000))
    }

    @Test
    fun `error retry preserves the interrupted track and position`() {
        assertEquals(PlaybackResumePoint(7, 40_000), playbackResumePoint(Player.STATE_IDLE, 7, 40_000))
    }

    @Test
    fun `error at end of file does not mark a book finished`() {
        assertFalse(finishedAtTermination(PlaybackTermination.ERROR, 100.seconds, 100.seconds))
    }

    @Test
    fun `natural completion and an explicit stop near end retain completion semantics`() {
        assertTrue(finishedAtTermination(PlaybackTermination.COMPLETED, 99.seconds, 100.seconds))
        assertTrue(finishedAtTermination(PlaybackTermination.STOP, 99.5.seconds, 100.seconds))
        assertFalse(finishedAtTermination(PlaybackTermination.STOP, 10.seconds, 100.seconds))
        assertFalse(finishedAtTermination(PlaybackTermination.STOP, 0.seconds, 0.seconds))
    }

    @Test
    fun `resume prepares idle media after an error and ended media after completion`() {
        assertTrue(needsPlaybackPreparation(Player.STATE_IDLE))
        assertTrue(needsPlaybackPreparation(Player.STATE_ENDED))
        assertFalse(needsPlaybackPreparation(Player.STATE_READY))
        assertFalse(needsPlaybackPreparation(Player.STATE_BUFFERING))
    }

    @Test
    fun `a retained book that stop already finalized is not finalized again by the next load`() {
        // Fresh playback still gets its terminal flush when the next book loads.
        assertTrue(shouldFinalizeRetainedBook(hasRetainedBook = true, alreadyFinalized = false))
        // stop() finalized the retained book already. Loading the next book after a
        // mode switch must not re-run that flush under the new mode.
        assertFalse(shouldFinalizeRetainedBook(hasRetainedBook = true, alreadyFinalized = true))
        // Nothing retained, nothing to finalize.
        assertFalse(shouldFinalizeRetainedBook(hasRetainedBook = false, alreadyFinalized = false))
        assertFalse(shouldFinalizeRetainedBook(hasRetainedBook = false, alreadyFinalized = true))
    }

    @Test
    fun `play on a stopped book with no media items reloads instead of resuming`() {
        assertTrue(shouldReloadRetainedBook(mediaItemCount = 0, hasRetainedBook = true))
    }

    @Test
    fun `play with media items or without a book resumes as before`() {
        assertFalse(shouldReloadRetainedBook(mediaItemCount = 8, hasRetainedBook = true))
        assertFalse(shouldReloadRetainedBook(mediaItemCount = 0, hasRetainedBook = false))
    }

    @Test
    fun `a tap on play while a book loads does nothing`() {
        assertEquals(
            PlayAction.IGNORE_WHILE_LOADING,
            playAction(bookLoading = true, hasRetainedBook = true, mediaItemCount = 0, playerState = Player.STATE_IDLE),
        )
        assertEquals(
            PlayAction.IGNORE_WHILE_LOADING,
            playAction(bookLoading = true, hasRetainedBook = true, mediaItemCount = 4, playerState = Player.STATE_READY),
        )
    }

    @Test
    fun `play sorts a retained book into reload, retry or resume`() {
        assertEquals(PlayAction.RELOAD_RETAINED, playAction(false, true, 0, Player.STATE_IDLE))
        assertEquals(PlayAction.RETRY_AFTER_TERMINAL, playAction(false, true, 4, Player.STATE_IDLE))
        assertEquals(PlayAction.RETRY_AFTER_TERMINAL, playAction(false, true, 4, Player.STATE_ENDED))
        assertEquals(PlayAction.RESUME, playAction(false, true, 4, Player.STATE_READY))
        assertEquals(PlayAction.RESUME, playAction(false, false, 0, Player.STATE_IDLE))
    }

    @Test
    fun `an error retry does not run while another book is loading`() {
        assertFalse(retainedRetryStillApplies("a", "a", samePlayer = true, playerNeedsPreparation = true, bookLoading = true))
        assertTrue(retainedRetryStillApplies("a", "a", samePlayer = true, playerNeedsPreparation = true, bookLoading = false))
        assertFalse(retainedRetryStillApplies("b", "a", true, true, false))
        assertFalse(retainedRetryStillApplies("a", "a", false, true, false))
        assertFalse(retainedRetryStillApplies("a", "a", true, false, false))
    }

    @Test(timeout = 5_000)
    fun `a stop that lands while the retry waits on the terminal write still finds the book finalized`() = runBlocking {
        val pending = PendingTerminalOwner()
        val gate = CompletableDeferred<Unit>()
        pending.launch(this, "a") { gate.await() }
        var finalized = true
        var current = true
        val calls = mutableListOf<String>()
        val retry = async {
            runTerminalRetry(
                pending = pending,
                bookId = "a",
                isCurrent = { current },
                claimOwnership = { calls += "claim"; true },
                rearm = { finalized = false; calls += "rearm" },
                prepare = { calls += "prepare" },
            )
        }
        yield()
        // While the retry waits, a stop or another load reads the flag. It
        // must still say finalized, so it skips a second terminal write.
        assertTrue(finalized)
        current = false
        gate.complete(Unit)
        assertFalse(retry.await())
        assertTrue(finalized)
        assertEquals(emptyList<String>(), calls)
    }

    @Test(timeout = 5_000)
    fun `the retry re-arms only after it reclaims ownership`() = runBlocking {
        val pending = PendingTerminalOwner()
        var finalized = true
        val calls = mutableListOf<String>()
        val prepared = runTerminalRetry(
            pending = pending,
            bookId = "a",
            isCurrent = { true },
            claimOwnership = { calls += "claim"; false },
            rearm = { finalized = false; calls += "rearm" },
            prepare = { calls += "prepare" },
        )
        assertFalse(prepared)
        assertTrue(finalized)
        assertEquals(listOf("claim"), calls)
    }

    @Test(timeout = 5_000)
    fun `a retry that wins re-arms and then prepares`() = runBlocking {
        val pending = PendingTerminalOwner()
        val gate = CompletableDeferred<Unit>()
        pending.launch(this, "a") { gate.await(); }
        var finalized = true
        val calls = mutableListOf<String>()
        val retry = async {
            runTerminalRetry(
                pending = pending,
                bookId = "a",
                isCurrent = { true },
                claimOwnership = { calls += "claim"; true },
                rearm = { finalized = false; calls += "rearm" },
                prepare = { calls += "prepare" },
            )
        }
        yield()
        assertEquals(emptyList<String>(), calls)
        gate.complete(Unit)
        assertTrue(retry.await())
        assertFalse(finalized)
        assertEquals(listOf("claim", "rearm", "prepare"), calls)
    }

    @Test(timeout = 5_000)
    fun `an error retry asks the server first, after the terminal write and before it prepares`() = runBlocking {
        val pending = PendingTerminalOwner()
        val calls = mutableListOf<String>()
        val prepared = runTerminalRetry(
            pending = pending,
            bookId = "a",
            isCurrent = { true },
            claimOwnership = { calls += "claim"; true },
            settle = { calls += "settle" },
            rearm = { calls += "rearm" },
            prepare = { calls += "prepare" },
        )
        assertTrue(prepared)
        assertEquals(listOf("claim", "settle", "rearm", "prepare"), calls)
    }

    @Test(timeout = 5_000)
    fun `a retry that lost the book while asking the server does not prepare`() = runBlocking {
        val pending = PendingTerminalOwner()
        var current = true
        val calls = mutableListOf<String>()
        val prepared = runTerminalRetry(
            pending = pending,
            bookId = "a",
            isCurrent = { current },
            claimOwnership = { calls += "claim"; true },
            settle = { calls += "settle"; current = false },
            rearm = { calls += "rearm" },
            prepare = { calls += "prepare" },
        )
        assertFalse(prepared)
        assertEquals(listOf("claim", "settle"), calls)
    }

    @Test(timeout = 5_000)
    fun `a leftover session closes after its in flight listening lands`() = runBlocking {
        // A stop with nothing new to save still closes the open session. Its
        // listening the server has not confirmed (a pause send in flight)
        // lands first, or the close cuts it off.
        val listening = ServerSessionListening()
        val sending = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val delivery = async {
            listening.deliver(30.0) { delta ->
                sending.complete(Unit)
                release.await()
                calls += "sync $delta"
                true
            }
        }
        sending.await()
        val close = async { closeLeftoverSession(listening) { calls += "close" } }
        yield()
        assertEquals(emptyList<String>(), calls)
        release.complete(Unit)
        delivery.await()
        close.await()
        assertEquals(listOf("sync 30.0", "close"), calls)
        // No listening known: the close still goes.
        val bare = mutableListOf<String>()
        closeLeftoverSession(null) { bare += "close" }
        assertEquals(listOf("close"), bare)
    }
}
