package com.ninelivesaudio.app.entitlement

/**
 * Turns the raw entitlement inputs into one [EntitlementState].
 *
 * Deliberately pure and free of Android types, because this is the piece that
 * has to be exhaustively unit-tested. Everything that can silently unlock the
 * whole app for everyone lives in this one function.
 *
 * There is no paid-era cutoff here and there must never be. The trial clock is
 * different: it starts only after an explicit tap, lasts a fixed 14 days, and
 * never creates a permanent entitlement.
 */
object EntitlementResolver {

    /**
     * @param legacyPaid the `legacy_paid` flag, written only by builds that
     *   predate the free switch. Nothing writes it any more.
     * @param playUnlocked a PURCHASED, non-refunded `nine_lives_unlock`, live or
     *   from the non-backed-up cache.
     * @param debugForceEntitled debug-build override. Callers must pass false in
     *   release builds.
     * @param forceFree release-build test override. Suppresses [EntitlementSource.LEGACY_PAID]
     *   and NOTHING else, so the internal-track pass can see the free tier on a
     *   device that already carries the flag without also hiding a real purchase.
     * @param trialStartedAtEpochMs the backed-up start written by an explicit
     *   trial action, or null if no start was ever recorded.
     * @param trialConsumed the backed-up one-time consumption latch.
     * @param nowEpochMs the wall-clock input used only by [TrialPolicy].
     */
    fun resolve(
        legacyPaid: Boolean,
        playUnlocked: Boolean,
        debugForceEntitled: Boolean = false,
        forceFree: Boolean = false,
        trialStartedAtEpochMs: Long? = null,
        trialConsumed: Boolean = false,
        nowEpochMs: Long = 0L,
    ): EntitlementState = when {
        debugForceEntitled -> EntitlementState(true, EntitlementSource.DEBUG)

        // A real purchase outranks the grandfather flag, and outranks forceFree
        // too. forceFree exists to test the FREE tier on a grandfathered device,
        // not to hide a purchase the user actually made.
        playUnlocked -> EntitlementState(true, EntitlementSource.PLAY_UNLOCK)

        legacyPaid && !forceFree -> EntitlementState(true, EntitlementSource.LEGACY_PAID)

        trialConsumed -> TrialPolicy.evaluate(nowEpochMs, trialStartedAtEpochMs)
            ?.let { active ->
                EntitlementState(
                    isUnlocked = true,
                    source = EntitlementSource.TRIAL,
                    trialDaysRemaining = active.daysRemaining,
                    trialEndsAtEpochMs = active.endsAtEpochMs,
                )
            }
            ?: EntitlementState.FREE

        else -> EntitlementState(
            isUnlocked = false,
            source = null,
            // A stray start with no consumed latch is malformed state. Fail
            // closed and do not offer a second start over the top of it.
            trialOfferAvailable = trialStartedAtEpochMs == null,
        )
    }
}
