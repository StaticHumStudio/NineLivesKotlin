package com.ninelivesaudio.app.entitlement

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The claim prompt is the ONLY channel between a past buyer and us, because
 * Play does not expose buyer email addresses for a paid-app order. A bug that
 * silences it is invisible: nobody complains about a dialog they never saw.
 */
class PaidEraClaimPolicyTest {

    /**
     * A real paid-era purchase, 2026-08-16 09:45:21 UTC.
     *
     * The Play order identifier is deliberately NOT recorded here. This repo is
     * public, and the claim flow verifies a claimant by the address they write
     * from, so publishing a live order id would hand strangers something to
     * impersonate with. The timestamp is all the test needs.
     */
    private val realBuyerInstall = 1786873521000L

    /** Jeff's own production install, 2026-04-18 15:34 UTC. Identifier omitted, same reason. */
    private val jeffsInstall = 1776526440000L

    @Test
    fun `the real paid customer is offered the claim`() {
        assertTrue(
            LegacyFreeMessage,
            PaidEraClaimPolicy.shouldPrompt(realBuyerInstall, isUnlocked = false, alreadyPrompted = false),
        )
    }

    @Test
    fun `Jeff's own paid install is offered the claim too`() {
        // His production install is Play-signed and never received the sideloaded
        // build that wrote legacy_paid, so he is in exactly the same boat.
        assertTrue(
            PaidEraClaimPolicy.shouldPrompt(jeffsInstall, isUnlocked = false, alreadyPrompted = false),
        )
    }

    @Test
    fun `an already unlocked reader is never prompted`() {
        assertFalse(
            "there is nothing to claim, and asking looks like a bug",
            PaidEraClaimPolicy.shouldPrompt(realBuyerInstall, isUnlocked = true, alreadyPrompted = false),
        )
    }

    @Test
    fun `the prompt is shown once and never again`() {
        assertFalse(
            "a nag box on every cold start earns a one-star faster than a missing feature",
            PaidEraClaimPolicy.shouldPrompt(realBuyerInstall, isUnlocked = false, alreadyPrompted = true),
        )
    }

    @Test
    fun `an install after the cutoff is not prompted`() {
        assertFalse(
            PaidEraClaimPolicy.shouldPrompt(
                PaidEraClaimPolicy.PROMPT_CUTOFF_MILLIS + 1,
                isUnlocked = false,
                alreadyPrompted = false,
            ),
        )
    }

    @Test
    fun `an unreadable install time is not prompted`() {
        listOf(0L, -1L).forEach { bogus ->
            assertFalse(
                "$bogus must not show a confusing dialog to everyone on the error path",
                PaidEraClaimPolicy.shouldPrompt(bogus, isUnlocked = false, alreadyPrompted = false),
            )
        }
    }

    /**
     * Unlike a grandfather cutoff, the safe direction here is LATER. If this
     * fails the release slipped past the prompt window and real buyers would be
     * silently skipped, so push the constant out before shipping.
     */
    @Test
    fun `the prompt window has not already closed`() {
        assertTrue(
            "PROMPT_CUTOFF_MILLIS has passed. Push it out, or paid-era buyers get no prompt at all.",
            PaidEraClaimPolicy.PROMPT_CUTOFF_MILLIS > System.currentTimeMillis(),
        )
    }

    private companion object {
        const val LegacyFreeMessage =
            "a real paid-era buyer must be offered the free unlock"
    }
}
