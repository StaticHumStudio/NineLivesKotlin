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
}
