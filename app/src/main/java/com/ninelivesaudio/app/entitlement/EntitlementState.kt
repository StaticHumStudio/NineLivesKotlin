package com.ninelivesaudio.app.entitlement

/**
 * Why an install is unlocked. Never why it is free, because free is the absence
 * of every source rather than a state of its own.
 */
enum class EntitlementSource {
    /**
     * This install predates the paid-to-free switch and carries `legacy_paid`.
     *
     * Read-only forever. The writer shipped in 2.0.2 and was deleted in the free
     * build, so nothing in this codebase can produce this source any more. See
     * [EntitlementPrefs].
     */
    LEGACY_PAID,

    /** A Play purchase of `nine_lives_unlock`, live or cached. */
    PLAY_UNLOCK,

    /** The one-time 14-day local trial, while its persisted clock is active. */
    TRIAL,

    /** Debug-build override. Never reachable in a release build. */
    DEBUG,
}

/**
 * The whole entitlement answer, in one value. Every gate observes this and
 * nothing queries billing directly.
 */
data class EntitlementState(
    val isUnlocked: Boolean,
    val source: EntitlementSource?,
    val trialOfferAvailable: Boolean = false,
    val trialDaysRemaining: Int? = null,
) {
    val isFree: Boolean get() = !isUnlocked

    companion object {
        /**
         * The starting value, and the answer whenever no source applies.
         *
         * Deliberately the default: an install resolves to free until something
         * proves otherwise. A Billing outage, a failed query, or a cold start
         * before the first query completes all land here, which is why revocation
         * needs a SUCCESSFUL empty query rather than merely a missing grant.
         */
        val FREE = EntitlementState(isUnlocked = false, source = null)
    }
}
