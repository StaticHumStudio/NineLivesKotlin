package com.ninelivesaudio.app.ui.unlock

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninelivesaudio.app.entitlement.BillingManager
import com.ninelivesaudio.app.entitlement.EntitlementRepository
import com.ninelivesaudio.app.entitlement.EntitlementSource
import com.ninelivesaudio.app.entitlement.EntitlementState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A restore result plus a nonce, so identical messages still emit. */
data class RestoreMessage(val id: Long, val text: String)

data class UnlockUiState(
    val entitlement: EntitlementState = EntitlementState.FREE,
    /** Localized price straight from Play, or null until Billing answers. */
    val formattedPrice: String? = null,
    /** False until Play has answered the product lookup one way or another. */
    val priceLookupSettled: Boolean = false,
) {
    val isUnlocked: Boolean get() = entitlement.isUnlocked

    /**
     * Grandfathered owners see a different screen. They own everything and there
     * is nothing to sell them, so the screen thanks them instead of offering a
     * purchase they must not be charged for.
     */
    val isGrandfathered: Boolean
        get() = entitlement.source == EntitlementSource.LEGACY_PAID

    /**
     * Play has not returned product details yet, so there is no real price to
     * show and no purchase to launch. Distinct from "unavailable": this is the
     * normal state for the first second or two after a cold start.
     */
    val isPriceLoading: Boolean
        get() = !isUnlocked && formattedPrice == null && !priceLookupSettled

    /**
     * Play answered and there is no purchasable product. Distinct from loading,
     * and the state a missing or misconfigured Console product produces.
     */
    val isPriceUnavailable: Boolean
        get() = !isUnlocked && formattedPrice == null && priceLookupSettled
}

@HiltViewModel
class UnlockViewModel @Inject constructor(
    private val billing: BillingManager,
    private val entitlements: EntitlementRepository,
) : ViewModel() {

    private val _forceFree = MutableStateFlow(entitlements.isForceFree)

    /**
     * State of the release-build test override.
     *
     * Only ever changed from this app, so mirroring the pref in a flow rather
     * than observing the file is sufficient.
     */
    val forceFree: StateFlow<Boolean> = _forceFree.asStateFlow()

    private val _restoreMessage = MutableStateFlow<RestoreMessage?>(null)

    /**
     * One-shot feedback for the restore button, cleared once shown.
     *
     * Carries a nonce because StateFlow conflates equal values. Two restores
     * inside the display window would otherwise emit the same string twice, the
     * second emission would be swallowed, and the dismissal timer would not
     * restart.
     */
    val restoreMessage: StateFlow<RestoreMessage?> = _restoreMessage.asStateFlow()

    private var restoreNonce = 0L

    val uiState: StateFlow<UnlockUiState> =
        combine(
            entitlements.state,
            billing.unlockProduct,
            billing.productLookupSettled,
        ) { entitlement, product, settled ->
            UnlockUiState(
                entitlement = entitlement,
                priceLookupSettled = settled,
                // Read the price from Play rather than hardcoding one. Prices
                // vary by region and the ladder moves with each feature drop.
                formattedPrice = product
                    ?.oneTimePurchaseOfferDetails
                    ?.formattedPrice,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            // Seeded from the repository rather than defaulting to FREE. The
            // default would show a purchase button to a grandfathered owner for
            // the frame before the first emission lands.
            initialValue = UnlockUiState(entitlement = entitlements.current),
        )

    fun purchase(activity: Activity) {
        // Last line of defence against the one unacceptable outcome. uiState is
        // a StateFlow with an initial value, so there is a window, however
        // narrow, where the UI has not caught up with entitlement yet. A
        // grandfathered owner has no Play purchase record, so Play would happily
        // charge them a second time for something they already own.
        if (entitlements.current.isUnlocked) return

        billing.launchPurchase(activity)
    }

    /**
     * Re-ask Play what this account owns.
     *
     * Purchases restore themselves on launch and on every foreground, so this
     * button is reassurance rather than mechanism. It still has to exist: a user
     * who reinstalls and does not immediately see their unlock needs something
     * to press before they email asking for a refund.
     */
    fun restorePurchases() {
        viewModelScope.launch {
            billing.refreshPurchases()
            _restoreMessage.value = RestoreMessage(++restoreNonce, "Checked with Google Play.")
        }
    }

    /**
     * Flip the release-build force-free override.
     *
     * The only physical test device carries the grandfather flag, so without
     * this the free tier is unobservable on real hardware during the
     * internal-track pass. Suppresses LEGACY_PAID only, never a real purchase.
     */
    fun toggleForceFree() {
        viewModelScope.launch {
            val next = !_forceFree.value
            entitlements.setForceFree(next)
            _forceFree.value = next
        }
    }

    fun consumeRestoreMessage() {
        _restoreMessage.value = null
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
