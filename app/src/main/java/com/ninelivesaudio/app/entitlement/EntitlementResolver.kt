package com.ninelivesaudio.app.entitlement

/**
 * Turns the raw entitlement inputs into one [EntitlementState].
 *
 * Deliberately pure and free of Android types, because this is the piece that
 * has to be exhaustively unit-tested. Everything that can silently unlock the
 * whole app for everyone lives in this one function.
 *
 * There is NO date logic here and there must never be. `PAID_ERA_CUTOFF` was
 * deleted on 2026-08-08: a cutoff constant has to be guessed before Play review
 * latency is knowable, and every install landing inside the guessed window
 * grandfathers itself permanently.
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
     */
    fun resolve(
        legacyPaid: Boolean,
        playUnlocked: Boolean,
        debugForceEntitled: Boolean = false,
        forceFree: Boolean = false,
    ): EntitlementState = when {
        debugForceEntitled -> EntitlementState(true, EntitlementSource.DEBUG)

        // A real purchase outranks the grandfather flag, and outranks forceFree
        // too. forceFree exists to test the FREE tier on a grandfathered device,
        // not to hide a purchase the user actually made.
        playUnlocked -> EntitlementState(true, EntitlementSource.PLAY_UNLOCK)

        legacyPaid && !forceFree -> EntitlementState(true, EntitlementSource.LEGACY_PAID)

        else -> EntitlementState.FREE
    }
}
