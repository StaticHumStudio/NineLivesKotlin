package com.ninelivesaudio.app.data.repository

import com.ninelivesaudio.app.data.local.entity.BookProgressState
import com.ninelivesaudio.app.data.local.entity.PlaybackProgressEntity
import com.ninelivesaudio.app.domain.util.toEpochMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #118: a book with no progress on the server is reset on the phone
 * only when the server once held progress for it, so the removal was made
 * there. Progress the server never received (an upgrade from 2.1.x knows of
 * no such record for any book) is delivered first, never wiped.
 */
class UnconfirmedProgressTest {

    @Test
    fun `absent on the server resets only progress the server once held`() {
        assertEquals(AbsentOnServer.RESET, absentOnServerAction(serverHeldIt = true))
        assertEquals(AbsentOnServer.DELIVER_FIRST, absentOnServerAction(serverHeldIt = false))
    }

    @Test
    fun `a book the server says is gone is left alone, not reset to the start`() {
        // Deleting a book drops its progress too. That is not a reset, and
        // the phone writes nothing for a book it can no longer play.
        assertEquals(AbsentOnServer.LEAVE, absentOnServerAction(serverHeldIt = true, serverSaidGone = true))
        assertEquals(AbsentOnServer.LEAVE, absentOnServerAction(serverHeldIt = false, serverSaidGone = true))
    }

    @Test
    fun `a saved spot goes as it was saved, at the time it was saved`() {
        val durable = PlaybackProgressEntity("b", 5_631.0, 0, "2026-11-14T22:00:00Z")
        val shelf = BookProgressState(id = "b", currentTimeSeconds = 5_631.0, progress = 0.15, durationSeconds = 36_000.0)

        val (progress, pending) = unconfirmedProgressRow("b", durable, shelf)!!

        // The saved row stays exactly as it is.
        assertEquals(durable, progress)
        assertEquals(5_631.0, pending.currentTime, 0.0)
        assertEquals(36_000.0, pending.duration, 0.0)
        assertEquals(1, pending.isAtomic)
        // The server orders it by when it happened, so another device's
        // later spot still wins over it.
        assertEquals("2026-11-14T22:00:00Z".toEpochMillis(), pending.timestamp.toEpochMillis())
    }

    @Test
    fun `a finished book goes as finished`() {
        val durable = PlaybackProgressEntity("b", 36_000.0, 1, null)

        val (_, pending) = unconfirmedProgressRow("b", durable, shelf = null)!!

        assertEquals(1, pending.isFinished)
        // No saved time claims nothing newer than anything the server holds.
        assertEquals(0L, pending.timestamp.toEpochMillis())
    }

    @Test
    fun `shelf progress with no saved spot goes too`() {
        val shelf = BookProgressState(id = "b", currentTimeSeconds = 0.0, progress = 0.25, durationSeconds = 3_600.0)

        val (progress, pending) = unconfirmedProgressRow("b", durable = null, shelf = shelf)!!

        assertEquals(900.0, progress.positionSeconds, 0.0)
        assertEquals(900.0, pending.currentTime, 0.0)
    }

    @Test
    fun `no progress anywhere sends nothing`() {
        assertNull(unconfirmedProgressRow("b", PlaybackProgressEntity("b", 0.0, 0, null), BookProgressState(id = "b")))
        assertNull(unconfirmedProgressRow("b", null, null))
    }
}
