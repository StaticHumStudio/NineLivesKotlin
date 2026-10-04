package com.ninelivesaudio.app.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPositionWriteCadenceTest {

    /** Feeds 500 ms poll samples through the real decision and cadence store. */
    private class FakePoll(
        val cadence: LocalPositionWriteCadence = LocalPositionWriteCadence(),
        var nowMs: Long = 1_000_000L,
    ) {
        val writesAt = mutableListOf<Long>()

        fun sample(
            force: Boolean = false,
            isFinished: Boolean = false,
            pushToServer: Boolean = false,
            pushSucceeds: Boolean = true,
        ) {
            val write = shouldWriteLocalPosition(
                cadence = cadence.snapshot("book"),
                nowMs = nowMs,
                force = force,
                isFinished = isFinished,
                pushToServer = pushToServer,
            )
            if (write) {
                writesAt += nowMs
                cadence.recordWrite("book", nowMs, pushFailed = pushToServer && !pushSucceeds)
            }
            nowMs += 500L
        }
    }

    @Test
    fun `a minute of playback saves every 10 seconds instead of every 500 ms`() {
        val poll = FakePoll()
        val start = poll.nowMs
        repeat(120) { poll.sample() }

        assertEquals(
            listOf(0L, 10_000L, 20_000L, 30_000L, 40_000L, 50_000L),
            poll.writesAt.map { it - start },
        )
    }

    @Test
    fun `the first sample of a book is saved`() {
        assertTrue(
            shouldWriteLocalPosition(
                cadence = LocalPositionCadenceSnapshot(),
                nowMs = 5L,
                force = false,
                isFinished = false,
                pushToServer = false,
            ),
        )
    }

    @Test
    fun `a forced sample is saved at once, and the 10 seconds restart from it`() {
        val poll = FakePoll()
        val start = poll.nowMs
        poll.sample() // t=0, saved
        repeat(5) { poll.sample() } // to t=2.5 s
        poll.sample(force = true) // t=3 s, a seek
        repeat(30) { poll.sample() }

        assertEquals(listOf(0L, 3_000L, 13_000L), poll.writesAt.map { it - start })
    }

    @Test
    fun `a finished book is saved at once`() {
        assertTrue(
            shouldWriteLocalPosition(
                cadence = LocalPositionCadenceSnapshot(lastWriteAtMs = 1_000L),
                nowMs = 1_500L,
                force = false,
                isFinished = true,
                pushToServer = false,
            ),
        )
    }

    @Test
    fun `a due server push goes out with a save at once`() {
        assertTrue(
            shouldWriteLocalPosition(
                cadence = LocalPositionCadenceSnapshot(lastWriteAtMs = 1_000L, lastPushFailed = false),
                nowMs = 1_500L,
                force = false,
                isFinished = false,
                pushToServer = true,
            ),
        )
    }

    @Test
    fun `after a failed push the retry waits for the 10 second save`() {
        val poll = FakePoll()
        val start = poll.nowMs
        // The push throttle stays due until a push lands, so every sample
        // asks to push. A server refusing connections fails each one.
        repeat(40) { poll.sample(pushToServer = true, pushSucceeds = false) }

        assertEquals(listOf(0L, 10_000L), poll.writesAt.map { it - start })
    }

    @Test
    fun `a clock that went backwards saves instead of waiting`() {
        assertTrue(
            shouldWriteLocalPosition(
                cadence = LocalPositionCadenceSnapshot(lastWriteAtMs = 50_000L),
                nowMs = 10_000L,
                force = false,
                isFinished = false,
                pushToServer = false,
            ),
        )
    }

    @Test
    fun `nothing is saved between cadence points`() {
        assertFalse(
            shouldWriteLocalPosition(
                cadence = LocalPositionCadenceSnapshot(lastWriteAtMs = 1_000L),
                nowMs = 1_000L + LOCAL_POSITION_WRITE_INTERVAL_MS - 1,
                force = false,
                isFinished = false,
                pushToServer = false,
            ),
        )
        assertEquals(10_000L, LOCAL_POSITION_WRITE_INTERVAL_MS)
    }

    @Test
    fun `the shelf write touches only the progress columns and skips the read when the duration is known`() = runBlocking {
        var reads = 0
        val updates = mutableListOf<Triple<Double, Double, Int>>()

        writeShelfProgressColumns(
            currentTime = 300.0,
            duration = 1_200.0,
            isFinished = false,
            readExistingProgress = { reads += 1; 0.9 },
            update = { time, progress, finished -> updates += Triple(time, progress, finished) },
        )

        assertEquals(0, reads)
        assertEquals(listOf(Triple(300.0, 0.25, 0)), updates)
    }

    @Test
    fun `an unknown duration keeps the stored progress`() = runBlocking {
        val updates = mutableListOf<Triple<Double, Double, Int>>()

        writeShelfProgressColumns(
            currentTime = 300.0,
            duration = 0.0,
            isFinished = false,
            readExistingProgress = { 0.4 },
            update = { time, progress, finished -> updates += Triple(time, progress, finished) },
        )

        assertEquals(listOf(Triple(300.0, 0.4, 0)), updates)
    }

    @Test
    fun `a finished book writes full progress and the finished flag`() = runBlocking {
        val updates = mutableListOf<Triple<Double, Double, Int>>()

        writeShelfProgressColumns(
            currentTime = 1_199.5,
            duration = 1_200.0,
            isFinished = true,
            readExistingProgress = { error("no read for a finished book") },
            update = { time, progress, finished -> updates += Triple(time, progress, finished) },
        )

        assertEquals(listOf(Triple(1_199.5, 1.0, 1)), updates)
    }
}
