package com.ninelivesaudio.app.entitlement

/**
 * Decides whether a `queryPurchasesAsync` response is a final answer from Play.
 *
 * Deliberately holds no Billing types, for the same reason [PurchaseEvaluator]
 * does not: the classification is the part that must be exhaustively tested,
 * and it should not need a Billing client, a Play Store, or a network to run.
 * `PurchaseGatePolicyTest` pins the mirrored values against the real library so
 * they cannot drift silently.
 */
object PurchaseGatePolicy {

    const val SERVICE_DISCONNECTED = -1
    const val SERVICE_UNAVAILABLE = 2
    const val NETWORK_ERROR = 12
    const val ERROR = 6

    /**
     * Codes where Play said NOTHING, as opposed to saying "no".
     *
     * These are the ones auto-reconnection or a plain retry can plausibly turn
     * into a real answer within seconds, so they must not settle the gate.
     * Everything else, including BILLING_UNAVAILABLE and DEVELOPER_ERROR, is a
     * stable answer that a retry would only repeat.
     */
    private val RETRYABLE = setOf(
        SERVICE_DISCONNECTED,
        SERVICE_UNAVAILABLE,
        NETWORK_ERROR,
        // ERROR is Play's generic "something went wrong on our side", which
        // Google documents as retryable. It reads like a hard failure and is
        // not one. Omitting it is what let a reinstalled unlock owner, whose
        // local Play cache is empty, get offered a free code for the unlock
        // they already bought.
        ERROR,
    )

    /** True when this response is an answer the gate may settle on. */
    fun settles(responseCode: Int): Boolean = responseCode !in RETRYABLE
}
