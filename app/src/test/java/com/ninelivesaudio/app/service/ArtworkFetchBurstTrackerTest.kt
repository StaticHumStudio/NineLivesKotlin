package com.ninelivesaudio.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The head-unit "notify storm": firing notifyChildrenChanged once per book
 * landing produced overlapping Android Auto re-queries, which could
 * complete out of order and made already-rendered art flicker away. This
 * tracker must coalesce a whole browse burst into one notify.
 */
class ArtworkFetchBurstTrackerTest {

    @Test
    fun `does not notify while fetches are still pending`() {
        val tracker = ArtworkFetchBurstTracker()
        tracker.begin()
        tracker.begin()
        assertFalse(tracker.end(landed = true)) // one of two still pending
    }

    @Test
    fun `notifies once the whole burst drains with at least one success`() {
        val tracker = ArtworkFetchBurstTracker()
        tracker.begin()
        tracker.begin()
        tracker.begin()
        assertFalse(tracker.end(landed = false))
        assertFalse(tracker.end(landed = true))
        assertTrue(tracker.end(landed = false)) // last one drains the burst
    }

    @Test
    fun `does not notify if nothing actually landed`() {
        val tracker = ArtworkFetchBurstTracker()
        tracker.begin()
        tracker.begin()
        assertFalse(tracker.end(landed = false))
        assertFalse(tracker.end(landed = false)) // drained, but nothing landed
    }

    @Test
    fun `a fetch scheduled while draining extends the batch instead of double-notifying`() {
        val tracker = ArtworkFetchBurstTracker()
        tracker.begin() // fetch A
        tracker.begin() // fetch B
        assertFalse(tracker.end(landed = true)) // A lands, B still pending

        // A new fetch (C) is scheduled — e.g. the user scrolled further —
        // before B has finished draining.
        tracker.begin()
        assertFalse(tracker.end(landed = false)) // B drains, C still pending: no premature notify
        assertTrue(tracker.end(landed = false)) // C drains: batch (including A's success) notifies now
    }

    @Test
    fun `one fetch at a time notifies on every landed completion`() {
        val tracker = ArtworkFetchBurstTracker()
        tracker.begin()
        assertTrue(tracker.end(landed = true))

        tracker.begin()
        assertTrue(tracker.end(landed = true))
    }

    @Test
    fun `only the first drain after a landing notifies, not every subsequent drain`() {
        val tracker = ArtworkFetchBurstTracker()
        tracker.begin()
        assertTrue(tracker.end(landed = true))

        // A second burst where nothing lands should not notify again.
        tracker.begin()
        assertFalse(tracker.end(landed = false))
    }
}
