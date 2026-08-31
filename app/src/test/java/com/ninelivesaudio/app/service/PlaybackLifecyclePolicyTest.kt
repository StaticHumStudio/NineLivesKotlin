package com.ninelivesaudio.app.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackLifecyclePolicyTest {

    @Test
    fun `paused restore does not open a listening session`() {
        assertEquals(
            ListeningSessionKind.NONE,
            listeningSessionToOpen(
                playbackStarting = false,
                isScannedLocalBook = true,
                hasServerSession = false,
                hasLocalSession = false,
            ),
        )
        assertEquals(
            ListeningSessionKind.NONE,
            listeningSessionToOpen(
                playbackStarting = false,
                isScannedLocalBook = false,
                hasServerSession = false,
                hasLocalSession = false,
            ),
        )
    }

    @Test
    fun `first playback opens the matching listening session once`() {
        assertEquals(
            ListeningSessionKind.LOCAL,
            listeningSessionToOpen(
                playbackStarting = true,
                isScannedLocalBook = true,
                hasServerSession = false,
                hasLocalSession = false,
            ),
        )
        assertEquals(
            ListeningSessionKind.SERVER,
            listeningSessionToOpen(
                playbackStarting = true,
                isScannedLocalBook = false,
                hasServerSession = false,
                hasLocalSession = false,
            ),
        )
        assertEquals(
            ListeningSessionKind.NONE,
            listeningSessionToOpen(
                playbackStarting = true,
                isScannedLocalBook = false,
                hasServerSession = true,
                hasLocalSession = false,
            ),
        )
    }

    @Test
    fun `natural completion clears the current item and replay saves it again`() {
        assertEquals(
            PlaybackItemPersistence.CLEAR,
            playbackItemPersistenceAction(naturalCompletion = true, playbackStarting = false),
        )
        assertEquals(
            PlaybackItemPersistence.SAVE,
            playbackItemPersistenceAction(naturalCompletion = false, playbackStarting = true),
        )
        assertEquals(
            PlaybackItemPersistence.KEEP,
            playbackItemPersistenceAction(naturalCompletion = false, playbackStarting = false),
        )
    }

    @Test
    fun `disconnect reset stops clears current book and waits for progress`() = runBlocking {
        val effects = mutableListOf<String>()

        runNowPlayingDisconnectReset(
            currentBookId = "book-19",
            invalidateActiveLoad = { effects += "invalidate" },
            stopPlayback = { effects += "stop" },
            clearCurrentBook = { effects += "clear" },
            awaitTerminalProgress = { bookId -> effects += "await $bookId" },
        )

        assertEquals(listOf("invalidate", "stop", "clear", "await book-19"), effects)
    }

    // ─── Disconnect barrier vs. a load that starts mid-teardown ───────────
    //
    // resetNowPlayingForDisconnect only invalidates the loads that already
    // existed when it started. A load kicked off while a confirmed disconnect
    // is still blocked awaiting terminal progress gets a brand new request id
    // and would otherwise claim it cleanly, surviving the logout it raced.

    @Test
    fun `barrier up refuses a remote load`() {
        assertTrue(disconnectBarrierBlocksLoad(barrierUp = true, bookIsLocal = false))
    }

    @Test
    fun `barrier up still allows a local load`() {
        assertFalse(disconnectBarrierBlocksLoad(barrierUp = true, bookIsLocal = true))
    }

    @Test
    fun `barrier down allows a remote load`() {
        assertFalse(disconnectBarrierBlocksLoad(barrierUp = false, bookIsLocal = false))
    }

    @Test
    fun `barrier down allows a local load`() {
        assertFalse(disconnectBarrierBlocksLoad(barrierUp = false, bookIsLocal = true))
    }

    // ─── Disconnect barrier counts overlapping disconnects ────────────────
    //
    // SettingsViewModel is a fresh instance every time Settings opens, but
    // PlaybackManager (and its barrier) is the singleton every instance
    // shares. Two raises can land before either lower does, and the count
    // must not reach zero until both operations have lowered their own.

    @Test
    fun `two overlapping raises need two lowers before the barrier drops`() {
        val barrier = DisconnectBarrier()

        barrier.raise()
        barrier.raise()
        barrier.lower()
        assertTrue(disconnectBarrierBlocksLoad(barrier.isUp, bookIsLocal = false))

        barrier.lower()
        assertFalse(disconnectBarrierBlocksLoad(barrier.isUp, bookIsLocal = false))
    }

    @Test
    fun `an unpaired lower floors at zero instead of wedging negative`() {
        val barrier = DisconnectBarrier()

        // Defensive: should not happen, but a stray extra lower must not
        // wedge the count negative and refuse every disconnect forever.
        barrier.lower()
        assertFalse(disconnectBarrierBlocksLoad(barrier.isUp, bookIsLocal = false))

        barrier.raise()
        assertTrue(disconnectBarrierBlocksLoad(barrier.isUp, bookIsLocal = false))
    }
}
