package com.ninelivesaudio.app.review

/**
 * The facts the eligibility gate runs on, with no Android types attached.
 *
 * Assembled by the caller. Kept separate so the policy below can be tested
 * exhaustively without a device, a Play Store, or a clock.
 */
data class ReviewSignals(
    val installAgeDays: Long,
    /** Books finished end to end. */
    val completedBooks: Int,
    /** Distinct listening sessions of real length, not app opens. */
    val listeningSessions: Int,
    /** Whether this process has already reported a crash. */
    val crashedThisSession: Boolean,
    /** Epoch millis of the last time the prompt was requested, or null. */
    val lastAttemptAt: Long?,
    val now: Long,
)

/**
 * Decides whether to ASK Play for a review prompt.
 *
 * Play's quota is silent and it may legitimately show nothing, so this is a
 * local pre-filter on top of that, not a guarantee. Its job is to make sure the
 * one attempt Play might honour lands on somebody who has actually used the app.
 *
 * ## Rules that are policy, not preference
 *
 * **No pre-screening.** There is deliberately no "are you enjoying the app?"
 * question anywhere in this flow. Asking first and routing only happy users to
 * the prompt violates Play policy, and it poisons the review corpus with a
 * filtered sample that stops being useful the moment anyone looks at it.
 *
 * **Never wired to a button.** Nothing here is reachable from a "rate us"
 * control. The quota is silent, so a button that sometimes does nothing reads as
 * broken. The manual path is a Settings row that deep-links to the listing.
 *
 * **Ungated by entitlement.** Free users are precisely the ones whose ratings
 * the funnel needs. Gating this behind the unlock would be self-defeating.
 */
object ReviewEligibility {

    /** Below this, the user has not lived with the app long enough to judge it. */
    const val MIN_INSTALL_AGE_DAYS = 7L

    /** Either a finished book, or this many real sessions, counts as real use. */
    const val MIN_LISTENING_SESSIONS = 5

    /**
     * Long on purpose. Play's own quota is the real limit, and asking again
     * quickly burns it against a user who has already declined once.
     */
    const val COOLDOWN_DAYS = 90L

    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    fun isEligible(signals: ReviewSignals): Boolean = with(signals) {
        if (installAgeDays < MIN_INSTALL_AGE_DAYS) return false

        // Real use, not a launch. One finished book is unambiguous, several real
        // sessions is the softer version for long books nobody has finished yet.
        val hasRealUse = completedBooks >= 1 || listeningSessions >= MIN_LISTENING_SESSIONS
        if (!hasRealUse) return false

        // Never ask someone to rate the app in the same session it fell over.
        if (crashedThisSession) return false

        val last = lastAttemptAt ?: return true
        // Defensive against a clock that moved backwards: treat a future
        // timestamp as a recent attempt rather than as eligible.
        if (last > now) return false

        return now - last >= COOLDOWN_DAYS * DAY_MILLIS
    }
}
