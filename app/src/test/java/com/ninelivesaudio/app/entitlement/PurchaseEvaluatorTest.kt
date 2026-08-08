package com.ninelivesaudio.app.entitlement

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The billing state machine, tested without a Billing client, a Play Store, or a
 * network. Every rule here has a failure mode that costs either the user their
 * money or us the paid tier, so all of them are pinned.
 */
class PurchaseEvaluatorTest {

    private val unlock = PurchaseEvaluator.UNLOCK_PRODUCT_ID

    private fun purchased(id: String = unlock, acknowledged: Boolean = true) =
        PurchaseSnapshot(id, isPurchased = true, isAcknowledged = acknowledged)

    private fun pending(id: String = unlock) =
        PurchaseSnapshot(id, isPurchased = false, isAcknowledged = false)

    // ─── Queries: the only thing allowed to revoke ────────────────────────────

    @Test
    fun `successful query containing the purchase grants`() {
        assertEquals(
            EntitlementVerdict.GRANT,
            PurchaseEvaluator.evaluateQuery(responseOk = true, purchases = listOf(purchased())),
        )
    }

    @Test
    fun `successful empty query revokes`() {
        assertEquals(
            EntitlementVerdict.REVOKE,
            PurchaseEvaluator.evaluateQuery(responseOk = true, purchases = emptyList()),
        )
    }

    /**
     * PENDING is not PURCHASED. A cash or parental-approval purchase that has not
     * cleared must not unlock, or the app is free to anyone willing to start a
     * payment and never finish it.
     */
    @Test
    fun `successful query with a PENDING purchase revokes`() {
        assertEquals(
            EntitlementVerdict.REVOKE,
            PurchaseEvaluator.evaluateQuery(responseOk = true, purchases = listOf(pending())),
        )
    }

    @Test
    fun `successful query holding only some other product revokes`() {
        assertEquals(
            EntitlementVerdict.REVOKE,
            PurchaseEvaluator.evaluateQuery(
                responseOk = true,
                purchases = listOf(purchased(id = "some_other_sku")),
            ),
        )
    }

    /**
     * The single most expensive mistake available here. A failed query is absence
     * of proof, not proof of absence, and treating it as a revocation would take
     * the app away from paying users every time Billing hiccups or the device is
     * offline.
     */
    @Test
    fun `a failed query never revokes`() {
        assertEquals(
            EntitlementVerdict.UNCHANGED,
            PurchaseEvaluator.evaluateQuery(responseOk = false, purchases = emptyList()),
        )
    }

    @Test
    fun `a failed query is not trusted even when it carries a purchase`() {
        assertEquals(
            EntitlementVerdict.UNCHANGED,
            PurchaseEvaluator.evaluateQuery(responseOk = false, purchases = listOf(purchased())),
        )
    }

    // ─── Purchase updates: may grant, never revoke ────────────────────────────

    @Test
    fun `a purchase update carrying the purchase grants`() {
        assertEquals(
            EntitlementVerdict.GRANT,
            PurchaseEvaluator.evaluatePurchaseUpdate(true, listOf(purchased())),
        )
    }

    /**
     * A dismissed or failed billing sheet arrives here with nothing attached. It
     * is not a census of what the user owns, so it must leave entitlement alone.
     * Treating it as a revocation would lock out anyone who opened the purchase
     * screen and changed their mind.
     */
    @Test
    fun `a cancelled billing flow leaves entitlement alone`() {
        assertEquals(
            EntitlementVerdict.UNCHANGED,
            PurchaseEvaluator.evaluatePurchaseUpdate(true, emptyList()),
        )
    }

    @Test
    fun `a PENDING purchase update does not grant`() {
        assertEquals(
            EntitlementVerdict.UNCHANGED,
            PurchaseEvaluator.evaluatePurchaseUpdate(true, listOf(pending())),
        )
    }

    @Test
    fun `a failed purchase update changes nothing`() {
        assertEquals(
            EntitlementVerdict.UNCHANGED,
            PurchaseEvaluator.evaluatePurchaseUpdate(false, listOf(purchased())),
        )
    }

    /** No purchase update may ever revoke, whatever it carries. */
    @Test
    fun `no purchase update input produces a revoke`() {
        val inputs = listOf(
            emptyList(),
            listOf(purchased()),
            listOf(pending()),
            listOf(purchased(id = "some_other_sku")),
        )
        for (ok in listOf(true, false)) {
            for (purchases in inputs) {
                val verdict = PurchaseEvaluator.evaluatePurchaseUpdate(ok, purchases)
                assertEquals(
                    "ok=$ok purchases=$purchases",
                    false,
                    verdict == EntitlementVerdict.REVOKE,
                )
            }
        }
    }

    // ─── Acknowledgement ──────────────────────────────────────────────────────

    /**
     * Google auto-refunds unacknowledged purchases after three days, so missing
     * one costs the user their money and us the sale.
     */
    @Test
    fun `an unacknowledged purchase needs acknowledgement`() {
        val target = purchased(acknowledged = false)

        assertEquals(listOf(target), PurchaseEvaluator.needsAcknowledgement(listOf(target)))
    }

    @Test
    fun `an already acknowledged purchase is left alone`() {
        assertEquals(
            emptyList<PurchaseSnapshot>(),
            PurchaseEvaluator.needsAcknowledgement(listOf(purchased(acknowledged = true))),
        )
    }

    /** Acknowledging a PENDING purchase is not valid, so it is never offered. */
    @Test
    fun `a PENDING purchase is never acknowledged`() {
        assertEquals(
            emptyList<PurchaseSnapshot>(),
            PurchaseEvaluator.needsAcknowledgement(listOf(pending())),
        )
    }
}
