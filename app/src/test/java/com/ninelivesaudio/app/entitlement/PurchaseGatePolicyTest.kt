package com.ninelivesaudio.app.entitlement

import com.android.billingclient.api.BillingClient.BillingResponseCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settle gate decides WHEN the claim prompt is allowed to trust its reading
 * of a user's purchases. Settling early on a code that a retry could have
 * answered means offering a free unlock code to somebody who already bought
 * one, which costs them a confusing dialog and us a support email.
 *
 * This set had no test before 2026-08-21, which is how ERROR stayed missing
 * from it through three review rounds.
 */
class PurchaseGatePolicyTest {

    @Test
    fun `a Play shrug does not settle the gate`() {
        // Codes where Play said nothing usable and a retry can plausibly fix it.
        assertFalse(PurchaseGatePolicy.settles(BillingResponseCode.SERVICE_DISCONNECTED))
        assertFalse(PurchaseGatePolicy.settles(BillingResponseCode.SERVICE_UNAVAILABLE))
        assertFalse(PurchaseGatePolicy.settles(BillingResponseCode.NETWORK_ERROR))
        assertFalse(PurchaseGatePolicy.settles(BillingResponseCode.ERROR))
    }

    @Test
    fun `a stable answer settles the gate`() {
        // A retry would only repeat these, so waiting only delays a correct call.
        assertTrue(PurchaseGatePolicy.settles(BillingResponseCode.OK))
        assertTrue(PurchaseGatePolicy.settles(BillingResponseCode.BILLING_UNAVAILABLE))
        assertTrue(PurchaseGatePolicy.settles(BillingResponseCode.DEVELOPER_ERROR))
        assertTrue(PurchaseGatePolicy.settles(BillingResponseCode.FEATURE_NOT_SUPPORTED))
        assertTrue(PurchaseGatePolicy.settles(BillingResponseCode.ITEM_UNAVAILABLE))
    }

    /**
     * ERROR is the regression this test exists for.
     *
     * A reinstalled unlock owner has no local Play cache. If that first
     * queryPurchasesAsync returns a transient ERROR and the gate settles on it,
     * the claim dialog reads "free" as established and offers them a code for
     * the unlock they already own. Google documents ERROR as retryable.
     */
    @Test
    fun `transient ERROR is treated as retryable, not as an answer`() {
        assertFalse(
            "ERROR is a Play shrug. Settling on it offers a paid user a free code.",
            PurchaseGatePolicy.settles(BillingResponseCode.ERROR),
        )
    }

    /**
     * Guards against the pure layer drifting from the Billing library it
     * mirrors. If Google renumbers a code, this fails rather than silently
     * reclassifying it.
     */
    @Test
    fun `mirrored codes still match the Billing library`() {
        assertEquals(BillingResponseCode.SERVICE_DISCONNECTED, PurchaseGatePolicy.SERVICE_DISCONNECTED)
        assertEquals(BillingResponseCode.SERVICE_UNAVAILABLE, PurchaseGatePolicy.SERVICE_UNAVAILABLE)
        assertEquals(BillingResponseCode.NETWORK_ERROR, PurchaseGatePolicy.NETWORK_ERROR)
        assertEquals(BillingResponseCode.ERROR, PurchaseGatePolicy.ERROR)
    }
}
