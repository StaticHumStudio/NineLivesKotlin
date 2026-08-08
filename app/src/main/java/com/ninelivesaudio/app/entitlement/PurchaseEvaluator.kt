package com.ninelivesaudio.app.entitlement

/**
 * A purchase reduced to the three facts entitlement actually depends on.
 *
 * Deliberately not a Play Billing type. The decision rules below are the part
 * that must be exhaustively tested, and they should not need a Billing client,
 * a Play Store, or a network to run.
 */
data class PurchaseSnapshot(
    val productId: String,
    /** True only for `PurchaseState.PURCHASED`. PENDING is not purchased. */
    val isPurchased: Boolean,
    val isAcknowledged: Boolean,
)

/** What a Billing result should do to persisted entitlement. */
enum class EntitlementVerdict {
    /** A confirmed purchase. Grant and cache it. */
    GRANT,

    /**
     * A successful query that contained no purchased instance. Revoke.
     *
     * The only path that may ever revoke. It requires proof of absence, not
     * merely absence of proof.
     */
    REVOKE,

    /**
     * Change nothing.
     *
     * Errors, timeouts, a disconnected Billing service, and cancelled billing
     * flows all land here. This is also why nothing that lands here may advance
     * the refresh generation: a no-op must not be able to discard a later real
     * result.
     */
    UNCHANGED,
}

/**
 * Decides what Billing results mean, with no Billing types involved.
 *
 * The asymmetry is the whole design. Granting needs a purchase. Revoking needs a
 * SUCCESSFUL query that came back without one. Anything less certain than that
 * leaves entitlement exactly where it was, because the failure mode of guessing
 * wrong is taking away something a user paid for while their network is flaky.
 */
object PurchaseEvaluator {

    const val UNLOCK_PRODUCT_ID = "nine_lives_unlock"

    /**
     * Evaluate the result of a `queryPurchasesAsync` call.
     *
     * @param responseOk true only for `BillingResponseCode.OK`. Every other
     *   code, plus a timeout and a disconnected service, is false.
     * @param purchases everything the query returned, unfiltered.
     */
    fun evaluateQuery(
        responseOk: Boolean,
        purchases: List<PurchaseSnapshot>,
    ): EntitlementVerdict = when {
        // Absence of proof. A failed query tells us nothing about what the user
        // owns, so it must never revoke.
        !responseOk -> EntitlementVerdict.UNCHANGED

        purchases.any { it.productId == UNLOCK_PRODUCT_ID && it.isPurchased } ->
            EntitlementVerdict.GRANT

        // Proof of absence. Reaching here means the query succeeded and the SKU
        // was either missing entirely or present but not PURCHASED, which covers
        // PENDING and refunded. Both revoke.
        else -> EntitlementVerdict.REVOKE
    }

    /**
     * Evaluate a `PurchasesUpdatedListener` callback.
     *
     * A purchase update is not a census. It reports what just happened, so it
     * can grant but must never revoke: a cancelled or failed billing flow says
     * nothing about purchases the user already owns.
     */
    fun evaluatePurchaseUpdate(
        responseOk: Boolean,
        purchases: List<PurchaseSnapshot>,
    ): EntitlementVerdict = when {
        !responseOk -> EntitlementVerdict.UNCHANGED

        purchases.any { it.productId == UNLOCK_PRODUCT_ID && it.isPurchased } ->
            EntitlementVerdict.GRANT

        else -> EntitlementVerdict.UNCHANGED
    }

    /**
     * Everything needing acknowledgement, from either a query or an update.
     *
     * Unacknowledged purchases are auto-refunded by Google after three days, so
     * this runs on query results too, not just on the purchase callback. An app
     * that only acknowledges in the update path loses every purchase whose
     * callback it missed, and out-of-app promo redemptions produce no callback
     * in this process at all.
     */
    fun needsAcknowledgement(purchases: List<PurchaseSnapshot>): List<PurchaseSnapshot> =
        purchases.filter { it.isPurchased && !it.isAcknowledged }
}
