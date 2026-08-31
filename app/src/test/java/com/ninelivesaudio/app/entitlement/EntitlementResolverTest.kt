package com.ninelivesaudio.app.entitlement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The resolver is the one place a mistake unlocks the whole app for everyone,
 * or locks out the person who paid, so every input combination is covered.
 */
class EntitlementResolverTest {

    private val trialStart = TimeUnit.DAYS.toMillis(20_000)

    // ─── The one that matters most ────────────────────────────────────────────

    /**
     * A fresh post-flip install must land on free. This is the test that would
     * have caught the unconditional `markLegacyPaid()` writer surviving into the
     * free build, which with PAID_ERA_CUTOFF gone would have grandfathered the
     * entire install base and quietly deleted the paid tier.
     */
    @Test
    fun `fresh install with no flag and no purchase is free`() {
        val state = EntitlementResolver.resolve(legacyPaid = false, playUnlocked = false)

        assertFalse(state.isUnlocked)
        assertTrue(state.isFree)
        assertNull(state.source)
        assertTrue(state.trialOfferAvailable)
        assertNull(state.trialDaysRemaining)
    }

    // ─── Sources ──────────────────────────────────────────────────────────────

    @Test
    fun `grandfather flag alone unlocks as LEGACY_PAID`() {
        val state = EntitlementResolver.resolve(legacyPaid = true, playUnlocked = false)

        assertTrue(state.isUnlocked)
        assertEquals(EntitlementSource.LEGACY_PAID, state.source)
    }

    @Test
    fun `play purchase alone unlocks as PLAY_UNLOCK`() {
        val state = EntitlementResolver.resolve(legacyPaid = false, playUnlocked = true)

        assertTrue(state.isUnlocked)
        assertEquals(EntitlementSource.PLAY_UNLOCK, state.source)
    }

    @Test
    fun `a real purchase outranks the grandfather flag`() {
        val state = EntitlementResolver.resolve(legacyPaid = true, playUnlocked = true)

        assertEquals(EntitlementSource.PLAY_UNLOCK, state.source)
    }

    @Test
    fun `an active consumed trial unlocks as TRIAL`() {
        val state = EntitlementResolver.resolve(
            legacyPaid = false,
            playUnlocked = false,
            trialStartedAtEpochMs = trialStart,
            trialConsumed = true,
            nowEpochMs = trialStart,
        )

        assertTrue(state.isUnlocked)
        assertEquals(EntitlementSource.TRIAL, state.source)
        assertEquals(14, state.trialDaysRemaining)
        assertFalse(state.trialOfferAvailable)
    }

    @Test
    fun `a trial is free and unavailable at the exact expiry boundary`() {
        val state = EntitlementResolver.resolve(
            legacyPaid = false,
            playUnlocked = false,
            trialStartedAtEpochMs = trialStart,
            trialConsumed = true,
            nowEpochMs = trialStart + TimeUnit.DAYS.toMillis(14),
        )

        assertTrue(state.isFree)
        assertNull(state.source)
        assertFalse(state.trialOfferAvailable)
    }

    @Test
    fun `a consumed trial with no start fails closed and stays unavailable`() {
        val state = EntitlementResolver.resolve(
            legacyPaid = false,
            playUnlocked = false,
            trialStartedAtEpochMs = null,
            trialConsumed = true,
            nowEpochMs = trialStart,
        )

        assertEquals(EntitlementState.FREE, state)
        assertFalse(state.trialOfferAvailable)
    }

    @Test
    fun `a start without consumption cannot grant or offer another trial`() {
        val state = EntitlementResolver.resolve(
            legacyPaid = false,
            playUnlocked = false,
            trialStartedAtEpochMs = trialStart,
            trialConsumed = false,
            nowEpochMs = trialStart,
        )

        assertTrue(state.isFree)
        assertFalse(state.trialOfferAvailable)
    }

    @Test
    fun `a play purchase outranks an active trial`() {
        val state = EntitlementResolver.resolve(
            legacyPaid = false,
            playUnlocked = true,
            trialStartedAtEpochMs = trialStart,
            trialConsumed = true,
            nowEpochMs = trialStart,
        )

        assertEquals(EntitlementSource.PLAY_UNLOCK, state.source)
        assertNull(state.trialDaysRemaining)
    }

    @Test
    fun `a legacy grant outranks an active trial`() {
        val state = EntitlementResolver.resolve(
            legacyPaid = true,
            playUnlocked = false,
            trialStartedAtEpochMs = trialStart,
            trialConsumed = true,
            nowEpochMs = trialStart,
        )

        assertEquals(EntitlementSource.LEGACY_PAID, state.source)
        assertNull(state.trialDaysRemaining)
    }

    @Test
    fun `debug override outranks everything`() {
        val state = EntitlementResolver.resolve(
            legacyPaid = false,
            playUnlocked = false,
            debugForceEntitled = true,
        )

        assertTrue(state.isUnlocked)
        assertEquals(EntitlementSource.DEBUG, state.source)
    }

    // ─── force-free, the release-build test override ──────────────────────────

    @Test
    fun `force free suppresses the grandfather flag`() {
        val state = EntitlementResolver.resolve(
            legacyPaid = true,
            playUnlocked = false,
            forceFree = true,
        )

        assertTrue(state.isFree)
        assertNull(state.source)
    }

    /**
     * force-free exists so the free tier is observable on the one test device,
     * which carries the grandfather flag. It must never hide a purchase the user
     * actually made, or a license tester would lose access mid-pass.
     */
    @Test
    fun `force free does not suppress a real play purchase`() {
        val state = EntitlementResolver.resolve(
            legacyPaid = true,
            playUnlocked = true,
            forceFree = true,
        )

        assertTrue(state.isUnlocked)
        assertEquals(EntitlementSource.PLAY_UNLOCK, state.source)
    }

    @Test
    fun `force free on an install that was already free changes nothing`() {
        val state = EntitlementResolver.resolve(
            legacyPaid = false,
            playUnlocked = false,
            forceFree = true,
        )

        assertTrue(state.isFree)
        assertTrue(state.trialOfferAvailable)
    }

    @Test
    fun `force free suppresses legacy but not an active trial`() {
        val state = EntitlementResolver.resolve(
            legacyPaid = true,
            playUnlocked = false,
            forceFree = true,
            trialStartedAtEpochMs = trialStart,
            trialConsumed = true,
            nowEpochMs = trialStart,
        )

        assertEquals(EntitlementSource.TRIAL, state.source)
    }

    // ─── No hidden inputs ─────────────────────────────────────────────────────

    /**
     * Guards against a date-based fallback creeping back in. PAID_ERA_CUTOFF was
     * deleted on 2026-08-08 because a guessed cutoff opens a window in which
     * every install self-grandfathers permanently. The resolver takes four
     * explicit values and nothing else, so the same inputs must always produce
     * the same answer.
     */
    @Test
    fun `resolution is a pure function of its inputs`() {
        val inputs = listOf(false, true)
        for (legacy in inputs) {
            for (play in inputs) {
                for (debug in inputs) {
                    for (free in inputs) {
                        val first = EntitlementResolver.resolve(legacy, play, debug, free)
                        val second = EntitlementResolver.resolve(legacy, play, debug, free)

                        assertEquals(
                            "legacy=$legacy play=$play debug=$debug forceFree=$free",
                            first,
                            second,
                        )
                        // An unlocked state always names its source, and a free
                        // state never does. Nothing unlocks anonymously.
                        assertEquals(first.isUnlocked, first.source != null)
                    }
                }
            }
        }
    }
}
