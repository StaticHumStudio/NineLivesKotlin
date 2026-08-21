/*
 * Nine Lives Audio is licensed under the GNU GPL version 3 with an additional
 * permission under section 7 covering the combination with the proprietary
 * Google Play Billing Library, which this file links against. The full terms
 * of that additional permission are stated at the top of the LICENSE file in
 * the repository root.
 */
package com.ninelivesaudio.app.entitlement

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything that talks to Play Billing. Nothing else in the app does.
 *
 * The decision rules live in [PurchaseEvaluator], deliberately separated so they
 * can be tested without a Billing client, a Play Store, or a network. This class
 * is the plumbing: connect, query, acknowledge, launch, and translate Billing
 * types into the three facts [PurchaseEvaluator] needs.
 *
 * ## The one rule worth restating
 *
 * Granting needs a purchase. Revoking needs a SUCCESSFUL query that came back
 * without one. Errors, timeouts and a disconnected service change nothing. Get
 * this backwards and every user on a flaky connection loses what they bought.
 */
@Singleton
class BillingManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val entitlements: EntitlementRepository,
) : PurchasesUpdatedListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Single-flights refreshes. The plan allows this in place of generation
     * stamping, and it is the simpler correct answer: with only one refresh ever
     * in flight there is no older result that could land after a newer one and
     * overwrite it.
     */
    private val refreshMutex = Mutex()

    private val _unlockProduct = MutableStateFlow<ProductDetails?>(null)

    /**
     * Product details for `nine_lives_unlock`, or null until Billing answers.
     *
     * The unlock screen reads its price from here rather than hardcoding one.
     * Prices vary by region and the ladder will move.
     */
    val unlockProduct: StateFlow<ProductDetails?> = _unlockProduct.asStateFlow()

    private val _productLookupSettled = MutableStateFlow(false)

    /**
     * True once a product lookup has finished, whatever it found.
     *
     * Without this, a null [unlockProduct] is ambiguous: it means both "Play has
     * not answered yet" and "Play answered and there is no such product". The
     * unlock screen would spin forever on the second one, which is exactly the
     * state a misconfigured or not-yet-created Console product produces.
     */
    val productLookupSettled: StateFlow<Boolean> = _productLookupSettled.asStateFlow()

    private val _purchaseQuerySettled = MutableStateFlow(false)

    /**
     * True once the first purchase query has produced an ANSWER.
     *
     * Same ambiguity as [productLookupSettled], one step more dangerous. Before
     * the first query answers, entitlement reads as free for everybody, because
     * the Play-grant cache is deliberately excluded from backup and so does not
     * survive a reinstall or a device move. Anything that acts on "user is free"
     * during that window acts on a value that has not been established yet.
     *
     * "Answer" excludes retryable failures. A disconnected service, a dead
     * network, or Play's generic ERROR is Play saying NOTHING, not Play saying
     * "you own nothing", and flipping this on one hands a consumer a provisional
     * free reading dressed up as an established one. See [PurchaseGatePolicy].
     *
     * This flow can therefore stay false forever, and that is deliberate: a device
     * with no Play Store never completes setup, so [refreshPurchases] never runs
     * there at all. Every consumer MUST carry its own bound rather than awaiting
     * this indefinitely.
     */
    val purchaseQuerySettled: StateFlow<Boolean> = _purchaseQuerySettled.asStateFlow()

    private val client: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        // One-time products can go PENDING (cash payments, parental approval).
        // A PENDING purchase is not PURCHASED and does not entitle.
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        // Billing 8+ reconnects with backoff on its own. This replaces the retry
        // loop the plan called for, and it is better than a hand-rolled one.
        .enableAutoServiceReconnection()
        .build()

    /**
     * Connect, then query. Call once from Application.onCreate.
     *
     * Also registers the foreground hook, because an out-of-app promo redemption
     * produces no purchase-update callback in this process. Without a query on
     * resume, redeeming a code and coming back leaves the app still locked.
     */
    fun start() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    scope.launch { refreshPurchases() }
                }
            }
        )
        connect()
    }

    private fun connect() {
        if (client.connectionState == BillingClient.ConnectionState.CONNECTED) return

        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    // Not an error worth surfacing. A device with no Play Store,
                    // or a Play Store mid-update, is a legitimate state. The user
                    // keeps whatever entitlement they already had.
                    Log.d(TAG, "billing setup finished: ${result.responseCode}")
                    return
                }
                scope.launch {
                    refreshPurchases()
                    loadProductDetails()
                }
            }

            override fun onBillingServiceDisconnected() {
                // Auto-reconnection handles the retry. Deliberately does NOT
                // revoke: a disconnected service is absence of proof.
                Log.d(TAG, "billing service disconnected")
            }
        })
    }

    /**
     * Ask Play what this account owns and apply the answer.
     *
     * Runs on launch, on every successful reconnect, and on every foreground.
     *
     * Skips outright when a refresh is already in flight, rather than queueing
     * behind it. Two concurrent queries would ask the same question and get the
     * same answer, and since this fires on every foreground, waiting on a lock
     * held across a slow network call would let app-switching pile up refreshes
     * that all land at once with nothing new to say.
     */
    suspend fun refreshPurchases() {
        if (!refreshMutex.tryLock()) {
            Log.d(TAG, "refresh already in flight, skipping")
            return
        }
        var answered = false
        try {
            // Bounded on purpose. The Billing KTX helpers suspend until Play
            // invokes their callback, and nothing guarantees it ever does. Without
            // a ceiling, one call that never resumes holds this lock for the life
            // of the process and every later launch, reconnect and foreground
            // refresh silently skips, leaving entitlement frozen forever.
            //
            // A timeout is not a revocation. It produces no verdict at all, which
            // is the same thing a failed query does.
            answered = withTimeoutOrNull(BILLING_TIMEOUT_MS) { queryAndApply() } ?: run {
                Log.d(TAG, "purchase query timed out, leaving entitlement untouched")
                // A timeout DOES settle the gate. Play had the full window and
                // produced nothing, so waiting past it buys a consumer nothing
                // except a longer stare at a spinner.
                true
            }
        } finally {
            // Settled out here rather than at the exits inside queryAndApply,
            // which withTimeoutOrNull cancels before they run, so the timeout path
            // never settled at all.
            //
            // Conditionally, though. Settling unconditionally was wrong for the
            // reason a second review pass caught: a retryable transport failure is
            // Play saying nothing, and treating it as an answer lets the paid-era
            // claim prompt act on a provisional free reading. An unlock owner
            // mid-reinstall could then be offered a free code for the thing they
            // already bought. Leaving it unsettled gives auto-reconnection a
            // window to land the real answer first.
            if (answered) _purchaseQuerySettled.value = true
            refreshMutex.unlock()
        }
    }

    /**
     * @return whether Play produced an answer, which is a strictly weaker claim
     *   than the query succeeding. A hard "no" (billing unavailable, developer
     *   error) IS an answer and settles the gate, because a retry will say the
     *   same thing. Only the retryable transport codes return false.
     */
    private suspend fun queryAndApply(): Boolean {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        val result = billingCall { client.queryPurchasesAsync(params) }
        if (result == null) {
            Log.d(TAG, "purchase query threw, leaving entitlement untouched")
            return false
        }

        val responseCode = result.billingResult.responseCode
        val responseOk = responseCode == BillingClient.BillingResponseCode.OK
        val snapshots = result.purchasesList.flatMap { it.toSnapshots() }

        when (PurchaseEvaluator.evaluateQuery(responseOk, snapshots)) {
            EntitlementVerdict.GRANT -> entitlements.applyPlayUnlock(true)
            EntitlementVerdict.REVOKE -> entitlements.applyPlayUnlock(false)
            EntitlementVerdict.UNCHANGED -> Unit
        }

        // Acknowledge from the query path too, not only from the purchase
        // callback. Google auto-refunds unacknowledged purchases after three
        // days, and a missed callback would otherwise cost the user their money
        // and us the sale.
        if (responseOk) acknowledgeIfNeeded(result.purchasesList)

        // Classification lives in PurchaseGatePolicy so it can be tested without
        // a Billing client. See PurchaseGatePolicyTest.
        if (!responseOk && !PurchaseGatePolicy.settles(responseCode)) {
            Log.d(TAG, "purchase query not answered ($responseCode), gate stays open")
            return false
        }
        return true
    }

    /** Load `nine_lives_unlock` so the unlock screen can show a real price. */
    suspend fun loadProductDetails() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PurchaseEvaluator.UNLOCK_PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()

        val result = withTimeoutOrNull(BILLING_TIMEOUT_MS) {
            billingCall { client.queryProductDetails(params) }
        }

        // Settled either way. A timeout, an error, and a successful lookup that
        // found nothing are all answers, and the UI has to be able to stop
        // waiting on each of them.
        _productLookupSettled.value = true

        if (result == null ||
            result.billingResult.responseCode != BillingClient.BillingResponseCode.OK
        ) {
            Log.d(TAG, "product lookup failed or timed out")
            return
        }

        _unlockProduct.value = result.productDetailsList
            ?.firstOrNull { it.productId == PurchaseEvaluator.UNLOCK_PRODUCT_ID }
    }

    /**
     * Open the Play purchase sheet.
     *
     * Returns null when product details have not loaded yet, which the caller
     * should treat as "not ready" rather than as a failure.
     */
    fun launchPurchase(activity: Activity): BillingResult? {
        val product = _unlockProduct.value ?: return null

        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(product)
                        .build()
                )
            )
            .build()

        return client.launchBillingFlow(activity, params)
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        val responseOk = result.responseCode == BillingClient.BillingResponseCode.OK
        val snapshots = purchases.orEmpty().flatMap { it.toSnapshots() }

        scope.launch {
            // A purchase update can grant but never revoke. A cancelled or
            // failed billing flow says nothing about what the user already owns,
            // and treating it as a census would revoke on every dismissed sheet.
            when (PurchaseEvaluator.evaluatePurchaseUpdate(responseOk, snapshots)) {
                EntitlementVerdict.GRANT -> entitlements.applyPlayUnlock(true)
                EntitlementVerdict.REVOKE,
                EntitlementVerdict.UNCHANGED,
                -> Unit
            }

            if (responseOk) acknowledgeIfNeeded(purchases.orEmpty())
        }
    }

    private suspend fun acknowledgeIfNeeded(purchases: List<Purchase>) {
        val pending = purchases.filter { purchase ->
            purchase.purchaseState == Purchase.PurchaseState.PURCHASED &&
                !purchase.isAcknowledged &&
                purchase.products.contains(PurchaseEvaluator.UNLOCK_PRODUCT_ID)
        }

        for (purchase in pending) {
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            val result = billingCall { client.acknowledgePurchase(params) }
            Log.d(TAG, "acknowledge: ${result?.responseCode}")
        }
    }

    /**
     * Run a Billing call, swallowing its failures but never its cancellation.
     *
     * runCatching would catch CancellationException too, which turns the timeout
     * above into a silent no-op that leaves the coroutine believing it is still
     * alive. Cancellation has to propagate.
     */
    private suspend fun <T> billingCall(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.d(TAG, "billing call failed: ${e.message}")
        null
    }

    private companion object {
        const val TAG = "BillingManager"

        /**
         * Ceiling on any single Billing round trip. Generous, because this is a
         * hang guard rather than a latency target.
         */
        const val BILLING_TIMEOUT_MS = 30_000L

        /**
         * One Play purchase can carry several product ids, so flatten rather
         * than assuming index zero.
         */
        fun Purchase.toSnapshots(): List<PurchaseSnapshot> = products.map { productId ->
            PurchaseSnapshot(
                productId = productId,
                isPurchased = purchaseState == Purchase.PurchaseState.PURCHASED,
                isAcknowledged = isAcknowledged,
            )
        }
    }
}
