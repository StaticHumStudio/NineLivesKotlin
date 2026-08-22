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

    private val productMutex = Mutex()

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
     *
     * The foreground hook loads the price as well as the purchases, and that is
     * the whole point rather than a convenience. See [loadProductDetails].
     */
    fun start() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    scope.launch {
                        refreshPurchases()
                        loadProductDetails()
                    }
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
                    //
                    // Nor is it rare. [enableAutoServiceReconnection] can already
                    // have a connection in flight when this call reaches the
                    // library, and the library rejects the loser with
                    // DEVELOPER_ERROR: "Client is already in the process of
                    // connecting to billing service." That happened on 10 of 10
                    // cold starts on a real device on 2026-08-22. The connection
                    // the other caller opened is live and serves queries fine,
                    // so this branch does not mean Play is unavailable. It means
                    // this particular listener will never hear back.
                    //
                    // Nothing may hang off this callback alone for that reason.
                    // Both calls below are also reachable from the foreground
                    // hook in [start], which is what makes them survive.
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
        try {
            // Bounded on purpose. The Billing KTX helpers suspend until Play
            // invokes their callback, and nothing guarantees it ever does. Without
            // a ceiling, one call that never resumes holds this lock for the life
            // of the process and every later launch, reconnect and foreground
            // refresh silently skips, leaving entitlement frozen forever.
            //
            // A timeout is not a revocation. It produces no verdict at all, which
            // is the same thing a failed query does.
            withTimeoutOrNull(BILLING_TIMEOUT_MS) { queryAndApply() }
                ?: Log.d(TAG, "purchase query timed out, leaving entitlement untouched")
        } finally {
            refreshMutex.unlock()
        }
    }

    private suspend fun queryAndApply() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        val result = billingCall { client.queryPurchasesAsync(params) }
        if (result == null) {
            Log.d(TAG, "purchase query threw, leaving entitlement untouched")
            return
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
    }

    /**
     * Load `nine_lives_unlock` so the unlock screen can show a real price.
     *
     * Runs on launch, on every successful connect, and on every foreground.
     *
     * The foreground trigger is not belt and braces. Until 2026-08-22 the only
     * caller was the success branch of [connect]'s setup listener, and that
     * listener loses a startup race with the library's own auto-reconnection
     * often enough to have failed 10 of 10 cold starts on a real device. When it
     * lost, this never ran, `productLookupSettled` stayed false for the life of
     * the process, and the unlock screen showed a disabled button spinning on
     * "Checking price" that no in-app action could clear. The Restore button did
     * not help: it re-asks what you own, not what things cost.
     *
     * The entitlement refresh survived the same race only because it had other
     * callers. This one did not. That asymmetry was the actual defect, so the
     * fix is a second trigger rather than a cleverer connect.
     *
     * Re-queries every time rather than caching the first answer. Prices vary by
     * region, the ladder moves with each feature drop, and the offer token
     * inside [ProductDetails] is what the purchase flow actually spends. A
     * player process can live for days, so a price pinned on first launch is a
     * price that can be wrong by the time somebody presses the button.
     *
     * A failed lookup leaves any previously good product in place rather than
     * clearing it, so one bad query never costs a working price.
     */
    suspend fun loadProductDetails() {
        // Two foregrounds inside one slow query would ask Play the same question
        // twice and write the same answer twice. Skip rather than queue, exactly
        // as [refreshPurchases] does.
        if (!productMutex.tryLock()) return
        try {
            loadProductDetailsLocked()
        } finally {
            productMutex.unlock()
        }
    }

    private suspend fun loadProductDetailsLocked() {
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

        // Matching the id is not enough. oneTimePurchaseOfferDetails is nullable
        // in the Billing API, and that field is both the price the screen shows
        // and the thing [launchPurchase] hands to Play. A matching product with
        // no offer prices as null, which disables the button just as thoroughly
        // as having no product at all, except it also looks like a cache hit and
        // so blocks the next lookup from replacing it. Treat it as a miss.
        val found = result.productDetailsList
            ?.firstOrNull {
                it.productId == PurchaseEvaluator.UNLOCK_PRODUCT_ID &&
                    it.oneTimePurchaseOfferDetails != null
            }

        // Only overwrite on a hit. An OK response is not a promise that our
        // product came back with it: Play reports unfetched products separately
        // from the overall response code, so an empty or non-matching list is a
        // routine outcome rather than proof the product is gone.
        //
        // Assigning that null unconditionally would clear a price we already had
        // and leave the button reading "Unavailable" with nothing the user can
        // press, which is the same dead purchase surface this whole change
        // exists to remove. Keeping the last known price risks the opposite and
        // much smaller failure: if the product really were deactivated, the user
        // taps buy and Play says no. A recoverable error beats a dead button.
        //
        // A device that never had a price keeps null and correctly shows the
        // unavailable copy, because that is the honest answer there.
        if (found != null) {
            _unlockProduct.value = found
        } else {
            Log.d(TAG, "product lookup found no matching product, keeping last known price")
        }
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
