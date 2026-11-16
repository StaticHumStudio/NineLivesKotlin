package com.ninelivesaudio.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Issue #118: a book loaded in the player but sitting still (the book a cold
 * start restores, or one paused for hours) takes the server's spot when
 * another device moved it. A playing book owns its progress, and a seek made
 * since the server was asked is never undone.
 */
class LoadedBookServerSpotTest {

    private fun takes(
        state: PlaybackState = PlaybackState.PAUSED,
        currentBookId: String? = "book",
        bookLoading: Boolean = false,
        hasMedia: Boolean = true,
        shown: Duration = 56.seconds,
        saved: Duration? = 56.seconds,
        target: Duration = 450.seconds,
    ) = loadedBookTakesServerSpot(
        spotBookId = "book",
        currentBookId = currentBookId,
        bookLoading = bookLoading,
        playbackState = state,
        hasMedia = hasMedia,
        shownPosition = shown,
        savedPosition = saved,
        target = target,
    )

    @Test
    fun `a paused restored book takes another device's later spot`() {
        assertTrue(takes())
        // Also when the spot is behind: another device rewound.
        assertTrue(takes(shown = 450.seconds, saved = 450.seconds, target = 56.seconds))
    }

    @Test
    fun `a playing or loading book keeps its own spot`() {
        assertFalse(takes(state = PlaybackState.PLAYING))
        assertFalse(takes(state = PlaybackState.BUFFERING))
        assertFalse(takes(state = PlaybackState.LOADING))
        assertFalse(takes(bookLoading = true))
        assertFalse(takes(hasMedia = false))
        assertFalse(takes(currentBookId = "other"))
    }

    @Test
    fun `a seek made since the server was asked is not undone`() {
        assertFalse(takes(shown = 3.minutes, saved = 56.seconds))
        // Not knowing the saved spot (Play asked just now) does not block it.
        assertTrue(takes(shown = 3.minutes, saved = null))
    }

    @Test
    fun `a spot the player already shows changes nothing`() {
        assertFalse(takes(target = 56.5.seconds))
    }

    @Test
    fun `play asks the server first for a remote book paused a while or restored`() {
        val now = 10_000_000L
        assertTrue(resumeAsksServerFirst(true, true, true, pausedAtMs = null, nowMs = now))
        assertTrue(resumeAsksServerFirst(true, true, true, pausedAtMs = now - RESUME_SETTLE_AFTER_PAUSE_MS, nowMs = now))
        // Paused a moment ago in this run: nothing else can plausibly have moved it.
        assertFalse(resumeAsksServerFirst(true, true, true, pausedAtMs = now - 5_000, nowMs = now))
        assertFalse(resumeAsksServerFirst(isRemoteBook = false, true, true, null, now))
        assertFalse(resumeAsksServerFirst(true, serverMode = false, true, null, now))
        assertFalse(resumeAsksServerFirst(true, true, serverReachable = false, null, now))
    }
}
