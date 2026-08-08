package com.ninelivesaudio.app.review

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The eligibility gate, pinned. Play's quota is silent, so a bug here is
 * invisible in testing: a prompt that never fires and a prompt that Play
 * declined to show look identical from inside the app.
 */
class ReviewEligibilityTest {

    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun signals(
        installAgeDays: Long = 30,
        completedBooks: Int = 1,
        listeningSessions: Int = 10,
        crashedThisSession: Boolean = false,
        lastAttemptAt: Long? = null,
        clock: Long = now,
    ) = ReviewSignals(
        installAgeDays = installAgeDays,
        completedBooks = completedBooks,
        listeningSessions = listeningSessions,
        crashedThisSession = crashedThisSession,
        lastAttemptAt = lastAttemptAt,
        now = clock,
    )

    @Test
    fun `a settled user with real listening history is eligible`() {
        assertTrue(ReviewEligibility.isEligible(signals()))
    }

    // ─── Install age ──────────────────────────────────────────────────────────

    @Test
    fun `a fresh install is not asked`() {
        assertFalse(ReviewEligibility.isEligible(signals(installAgeDays = 6)))
    }

    @Test
    fun `the age threshold is inclusive`() {
        assertTrue(ReviewEligibility.isEligible(signals(installAgeDays = 7)))
    }

    // ─── Real use ─────────────────────────────────────────────────────────────

    @Test
    fun `an install that has never really been used is not asked`() {
        assertFalse(
            ReviewEligibility.isEligible(signals(completedBooks = 0, listeningSessions = 0))
        )
    }

    @Test
    fun `one completed book is enough on its own`() {
        assertTrue(
            ReviewEligibility.isEligible(signals(completedBooks = 1, listeningSessions = 0))
        )
    }

    /** Long books exist. Nobody finishes a 40-hour book in week one. */
    @Test
    fun `enough real sessions count even with nothing finished`() {
        assertTrue(
            ReviewEligibility.isEligible(signals(completedBooks = 0, listeningSessions = 5))
        )
        assertFalse(
            ReviewEligibility.isEligible(signals(completedBooks = 0, listeningSessions = 4))
        )
    }

    // ─── Crash ────────────────────────────────────────────────────────────────

    /** Never ask somebody to rate the app in the session it fell over in. */
    @Test
    fun `a session that crashed is never asked`() {
        assertFalse(ReviewEligibility.isEligible(signals(crashedThisSession = true)))
    }

    // ─── Cooldown ─────────────────────────────────────────────────────────────

    @Test
    fun `a recent attempt blocks another`() {
        assertFalse(
            ReviewEligibility.isEligible(signals(lastAttemptAt = now - 30 * day))
        )
    }

    @Test
    fun `the cooldown expires`() {
        assertTrue(
            ReviewEligibility.isEligible(signals(lastAttemptAt = now - 91 * day))
        )
    }

    @Test
    fun `the cooldown boundary is inclusive`() {
        assertTrue(
            ReviewEligibility.isEligible(
                signals(lastAttemptAt = now - ReviewEligibility.COOLDOWN_DAYS * day)
            )
        )
    }

    /**
     * A device whose clock jumped backwards would otherwise compute a negative
     * elapsed time and read as "cooldown expired", asking again immediately.
     */
    @Test
    fun `a future last-attempt timestamp does not unlock the prompt`() {
        assertFalse(
            ReviewEligibility.isEligible(signals(lastAttemptAt = now + 10 * day))
        )
    }

    // ─── Every gate is independently blocking ─────────────────────────────────

    @Test
    fun `each condition alone is enough to block`() {
        assertFalse(ReviewEligibility.isEligible(signals(installAgeDays = 0)))
        assertFalse(ReviewEligibility.isEligible(signals(completedBooks = 0, listeningSessions = 0)))
        assertFalse(ReviewEligibility.isEligible(signals(crashedThisSession = true)))
        assertFalse(ReviewEligibility.isEligible(signals(lastAttemptAt = now)))
    }
}
