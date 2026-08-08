package com.ninelivesaudio.app.entitlement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The resolver is the one place a mistake unlocks the whole app for everyone,
 * or locks out the person who paid, so every input combination is covered.
 */
class EntitlementResolverTest {

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
        assertEquals(EntitlementState.FREE, state)
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

        assertEquals(EntitlementState.FREE, state)
    }

    // ─── No hidden inputs ─────────────────────────────────────────────────────

    /**
     * Guards against a date-based fallback creeping back in. PAID_ERA_CUTOFF was
     * deleted on 2026-08-08 because a guessed cutoff opens a window in which
     * every install self-grandfathers permanently. The resolver takes four
     * booleans and nothing else, so the same four inputs must always produce the
     * same answer no matter when it runs.
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
