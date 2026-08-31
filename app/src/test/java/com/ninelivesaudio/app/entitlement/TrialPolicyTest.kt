package com.ninelivesaudio.app.entitlement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class TrialPolicyTest {

    private val start = TimeUnit.DAYS.toMillis(20_000)

    @Test
    fun `a trial starts with fourteen days remaining`() {
        val active = TrialPolicy.evaluate(nowEpochMs = start, startedAtEpochMs = start)

        assertEquals(14, active?.daysRemaining)
        assertEquals(start + TimeUnit.DAYS.toMillis(14), active?.endsAtEpochMs)
    }

    @Test
    fun `a partial final day is reported as one day remaining`() {
        val now = start + TimeUnit.DAYS.toMillis(14) - 1

        assertEquals(1, TrialPolicy.evaluate(now, start)?.daysRemaining)
    }

    @Test
    fun `a trial remains active one millisecond before the boundary`() {
        val now = start + TimeUnit.DAYS.toMillis(14) - 1

        assertTrue(TrialPolicy.evaluate(now, start) != null)
    }

    @Test
    fun `a trial expires exactly at fourteen days`() {
        val now = start + TimeUnit.DAYS.toMillis(14)

        assertNull(TrialPolicy.evaluate(now, start))
    }

    @Test
    fun `a missing start never grants a trial`() {
        assertNull(TrialPolicy.evaluate(nowEpochMs = start, startedAtEpochMs = null))
    }

    @Test
    fun `an invalid persisted epoch fails closed`() {
        assertNull(TrialPolicy.evaluate(nowEpochMs = start, startedAtEpochMs = -1))
        assertNull(
            TrialPolicy.evaluate(
                nowEpochMs = start,
                startedAtEpochMs = Long.MAX_VALUE,
            )
        )
    }

    @Test
    fun `a start within the clock jitter tolerance remains active`() {
        val nowBeforeStart = start - TimeUnit.MINUTES.toMillis(5) + 1

        assertEquals(14, TrialPolicy.evaluate(nowBeforeStart, start)?.daysRemaining)
    }

    @Test
    fun `a start one hour in the future fails closed`() {
        val futureStart = start + TimeUnit.HOURS.toMillis(1)

        assertNull(TrialPolicy.evaluate(start, futureStart))
    }

    @Test
    fun `a start one year in the future fails closed`() {
        val futureStart = start + TimeUnit.DAYS.toMillis(365)

        assertNull(TrialPolicy.evaluate(start, futureStart))
    }

    // ─── High-water mark (clock rollback resistance) ──────────────────────────

    @Test
    fun `a clock rollback inside the trial window cannot revive an expired trial`() {
        // The trial was already observed to reach its expiry boundary...
        val latestSeen = start + TimeUnit.DAYS.toMillis(14)
        // ...then the device clock gets set back to day one, well inside the
        // original 14-day window.
        val rolledBackNow = start + TimeUnit.DAYS.toMillis(1)

        assertNull(TrialPolicy.evaluate(rolledBackNow, start, latestSeen))
    }

    @Test
    fun `a clock rollback past the boundary stays expired no matter how far back it goes`() {
        val latestSeen = start + TimeUnit.DAYS.toMillis(20)
        val rolledBackNow = start - TimeUnit.DAYS.toMillis(365)

        assertNull(TrialPolicy.evaluate(rolledBackNow, start, latestSeen))
    }

    @Test
    fun `ordinary forward clock movement is unaffected by the high-water mark`() {
        val now = start + TimeUnit.DAYS.toMillis(5)

        val withMark = TrialPolicy.evaluate(now, start, latestSeenEpochMs = start)
        val withoutMark = TrialPolicy.evaluate(now, start, latestSeenEpochMs = null)

        assertEquals(withoutMark, withMark)
        assertEquals(9, withMark?.daysRemaining)
    }

    @Test
    fun `the high-water mark can only push the effective clock forward, never back`() {
        val now = start + TimeUnit.DAYS.toMillis(5)
        val laterMark = start + TimeUnit.DAYS.toMillis(10)
        val earlierMark = start + TimeUnit.DAYS.toMillis(2)

        // A mark ahead of now wins: evaluating with (now, mark=later) matches
        // evaluating as if now had actually reached the mark.
        assertEquals(
            TrialPolicy.evaluate(laterMark, start)?.daysRemaining,
            TrialPolicy.evaluate(now, start, laterMark)?.daysRemaining,
        )
        // A mark behind now never pulls the effective clock backward: it changes
        // nothing versus not having a mark at all.
        assertEquals(
            TrialPolicy.evaluate(now, start)?.daysRemaining,
            TrialPolicy.evaluate(now, start, earlierMark)?.daysRemaining,
        )
    }

    @Test
    fun `the future-start tolerance still checks the raw clock, not the watermark`() {
        // A stale high-water mark sitting far in the future must not be used to
        // wave through a start that the raw clock says is implausible.
        val futureStart = start + TimeUnit.HOURS.toMillis(1)
        val staleMark = start + TimeUnit.DAYS.toMillis(365)

        assertNull(TrialPolicy.evaluate(start, futureStart, staleMark))
    }
}
