package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.data.remote.ServerProgressRead
import com.ninelivesaudio.app.domain.model.UserProgress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Where a reloaded book starts (#106, #118). A finished book parks at its end
 * (Jeff decided 2026-11-02: finished books reopen from the end). The most
 * recent spot wins, never the furthest (#118): the server's spot, read after
 * the phone's listening reached it, then the saved spot, then the one the
 * load was handed.
 */
class LoadStartPositionTest {

    private val end = 10.hours

    /**
     * Mirrors the load: the server's answer, the saved spot, the handed one,
     * then where the book is placed. A null [sessionTime] is a load with no
     * server session open, a null [server] a load that got no server answer.
     * [unsynced] is whether the phone holds listening the server has not
     * got, which the older cases here all do (it is what made their saved
     * spot newer than the session's).
     */
    private fun placementOfReload(
        bookTime: Duration,
        sessionTime: Duration?,
        saved: Duration?,
        server: ServerProgressRead? = null,
        unsynced: Boolean = true,
    ): LoadPlacement = runBlocking {
        var written: Duration? = null
        val resolved = PlaybackProgressOwner().resolveAndSavePlaybackPosition(
            bookId = "book",
            server = serverLoadPosition(server, end) ?: sessionLoadPosition(sessionTime, unsynced),
            candidate = handedLoadPosition(bookTime, sessionTime),
            readDurable = { saved },
            save = { written = it },
        )
        assertEquals(resolved, written)
        loadPlacement(resolved, end, autoPlay = true)
    }

    /** Where the reload shows the book, for a book that is not parked at its end. */
    private fun startOfReload(
        bookTime: Duration,
        sessionTime: Duration?,
        saved: Duration?,
        server: ServerProgressRead? = null,
        unsynced: Boolean = true,
    ): Duration {
        val placement = placementOfReload(bookTime, sessionTime, saved, server, unsynced)
        assertFalse(placement.parkedAtEnd)
        assertEquals(placement.shownPosition, placement.playerStart)
        assertTrue(placement.playWhenReady)
        return placement.shownPosition
    }

    /** A finished book parks: shown at its end, paused, the player waiting at 0:00. */
    private fun assertParkedAtEnd(placement: LoadPlacement, shownEnd: Duration = end) {
        assertTrue(placement.parkedAtEnd)
        assertEquals(shownEnd, placement.shownPosition)
        assertEquals(Duration.ZERO, placement.playerStart)
        assertFalse(placement.playWhenReady)
    }

    @Test
    fun `a restarted book resumes its saved spot over a stale end position in memory`() {
        // Finished, restarted from 0:00, listened to 3:00. The book object
        // still says it is at the end.
        assertEquals(3.minutes, startOfReload(bookTime = end, sessionTime = Duration.ZERO, saved = 3.minutes))
    }

    @Test
    fun `a restarted book resumes its saved spot over a server session still at the end`() {
        assertEquals(3.minutes, startOfReload(bookTime = Duration.ZERO, sessionTime = end, saved = 3.minutes))
    }

    // Changed expectation: these two started over at 0:00. Jeff decided
    // 2026-11-02: finished books reopen from the end.
    @Test
    fun `a book reloaded right after it finished parks at its end`() {
        assertParkedAtEnd(placementOfReload(bookTime = end, sessionTime = end, saved = end))
    }

    @Test
    fun `a finished book with no saved row parks at its end`() {
        // No server session open (a paused restore, or a download offline).
        // A missing session is no position at all, not a 0:00 that would hide
        // the book's end.
        assertParkedAtEnd(placementOfReload(bookTime = end, sessionTime = null, saved = null))
    }

    private fun found(position: Duration, finished: Boolean = false) =
        ServerProgressRead.Found(UserProgress(libraryItemId = "book", currentTime = position, isFinished = finished))

    @Test
    fun `the server's spot beats the saved one, older or newer`() {
        // Another device listened further.
        assertEquals(2.hours, startOfReload(bookTime = 1.hours, sessionTime = null, saved = 1.hours, server = found(2.hours)))
        // Another device rewound. The furthest spot used to win and undo it.
        assertEquals(
            30.minutes,
            startOfReload(bookTime = 2.hours, sessionTime = 2.hours, saved = 2.hours, server = found(30.minutes)),
        )
    }

    @Test
    fun `a server session's spot does not beat a saved spot the server has not got`() {
        // The phone holds listening the server has not judged yet (its
        // delivery before the session failed), so its saved spot is the
        // latest it knows.
        assertEquals(1.hours, startOfReload(bookTime = 1.hours, sessionTime = 2.hours, saved = 1.hours))
        assertEquals(1.hours, startOfReload(bookTime = 1.hours, sessionTime = 2.hours, saved = 1.hours, server = ServerProgressRead.Unavailable))
    }

    @Test
    fun `with nothing unsent, an open session's spot is the server's and beats the saved one`() {
        // #118 review: the check before the load timed out but /play opened.
        // The session's spot is the server's, and the saved spot has nothing
        // behind it the server lacks, so another device's spot wins.
        assertEquals(2.hours, startOfReload(bookTime = 1.hours, sessionTime = 2.hours, saved = 1.hours, unsynced = false))
        assertEquals(
            30.minutes,
            startOfReload(bookTime = 2.hours, sessionTime = 30.minutes, saved = 2.hours, server = ServerProgressRead.Unavailable, unsynced = false),
        )
        // A server answer still comes first.
        assertEquals(
            40.minutes,
            startOfReload(bookTime = 2.hours, sessionTime = 30.minutes, saved = 2.hours, server = found(40.minutes), unsynced = false),
        )
        // A session at 0:00 says nothing: the server opens a finished book there.
        assertParkedAtEnd(placementOfReload(bookTime = end, sessionTime = Duration.ZERO, saved = end, unsynced = false))
        assertEquals(null, sessionLoadPosition(sessionTime = null, hasUnsyncedListening = false))
    }

    @Test
    fun `the saved spot beats the one in memory, further or not`() {
        assertEquals(2.hours, startOfReload(bookTime = 1.hours, sessionTime = Duration.ZERO, saved = 2.hours))
        assertEquals(1.hours, startOfReload(bookTime = 2.hours, sessionTime = null, saved = 1.hours))
    }

    @Test
    fun `progress removed on the server starts the book over`() {
        assertEquals(Duration.ZERO, startOfReload(bookTime = 2.hours, sessionTime = null, saved = 2.hours, server = ServerProgressRead.Missing))
    }

    @Test
    fun `a book the server holds as finished parks at its end`() {
        assertParkedAtEnd(placementOfReload(bookTime = 1.hours, sessionTime = Duration.ZERO, saved = 1.hours, server = found(3.minutes, finished = true)))
    }

    @Test
    fun `with no saved spot a session's spot beats the book's, unless it is the start`() {
        assertEquals(2.hours, handedLoadPosition(bookTime = 1.hours, sessionTime = 2.hours))
        assertEquals(30.minutes, handedLoadPosition(bookTime = 1.hours, sessionTime = 30.minutes))
        // The server opens a finished book at 0:00. That is not a spot.
        assertEquals(end, handedLoadPosition(bookTime = end, sessionTime = Duration.ZERO))
        assertEquals(end, handedLoadPosition(bookTime = end, sessionTime = null))
    }

    // A stop in the last second saves the book as finished, so the load
    // treats the same window as the end (one shared rule).

    @Test
    fun `a stale position half a second from the end does not beat the saved spot`() {
        // Stopped at the end minus 0.5 s (saved as finished), restarted, listened 10 min.
        val nearEnd = end - 500.milliseconds
        assertEquals(10.minutes, startOfReload(bookTime = nearEnd, sessionTime = Duration.ZERO, saved = 10.minutes))
        assertEquals(10.minutes, startOfReload(bookTime = Duration.ZERO, sessionTime = nearEnd, saved = 10.minutes))
    }

    // Changed expectation: this started over at 0:00. Jeff decided
    // 2026-11-02: finished books reopen from the end, and a book stopped in
    // its last second is a finished book.
    @Test
    fun `a book stopped half a second from the end parks at its end like a finished one`() {
        val nearEnd = end - 500.milliseconds
        assertParkedAtEnd(placementOfReload(bookTime = nearEnd, sessionTime = nearEnd, saved = nearEnd))
        assertEquals(loadPlacement(end, end, autoPlay = true), loadPlacement(nearEnd, end, autoPlay = true))
    }

    @Test
    fun `the load and the stop share one end of book window`() {
        val nearEnd = end - 500.milliseconds
        assertTrue(finishedAtTermination(PlaybackTermination.STOP, nearEnd, end))
        assertTrue(positionAtBookEnd(nearEnd, end))
        assertFalse(positionAtBookEnd(end - 1500.milliseconds, end))
        assertFalse(finishedAtTermination(PlaybackTermination.STOP, end - 1500.milliseconds, end))
    }

    @Test
    fun `a track sum a little shorter than the server length still counts as the end`() {
        // The player's duration is the sum of its tracks, the load's is the server's.
        val trackSumEnd = end - 300.milliseconds
        assertTrue(positionAtBookEnd(trackSumEnd, end))
        assertEquals(10.minutes, startOfReload(bookTime = trackSumEnd, sessionTime = Duration.ZERO, saved = 10.minutes))
        // A track sum a little longer is past the server end, which is the end
        // too. Changed expectation: this started over at 0:00 (Jeff decided
        // 2026-11-02: finished books reopen from the end).
        assertParkedAtEnd(loadPlacement(end + 300.milliseconds, end, autoPlay = true))
    }
}
