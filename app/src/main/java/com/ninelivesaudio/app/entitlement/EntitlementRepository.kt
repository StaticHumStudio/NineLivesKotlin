package com.ninelivesaudio.app.entitlement

import com.ninelivesaudio.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single source of truth for entitlement. Every gate observes [state] and
 * nothing anywhere queries billing directly.
 *
 * Billing is not wired in yet. [applyPlayUnlock] is the seam the BillingManager
 * plugs into, and until then the repository resolves from the grandfather flag
 * and the cached grant alone.
 */
@Singleton
class EntitlementRepository @Inject constructor(
    private val prefs: EntitlementPrefs,
    private val cache: EntitlementCachePrefs,
) {
    private val _state = MutableStateFlow(resolveNow())
    val state: StateFlow<EntitlementState> = _state.asStateFlow()

    /**
     * Serializes every write. Entitlement is read from several places at once
     * and a torn read-modify-write here unlocks or locks the whole app.
     */
    private val writeMutex = Mutex()

    val current: EntitlementState get() = _state.value

    /** Whether the release-build force-free override is currently on. */
    val isForceFree: Boolean get() = cache.forceFree

    /**
     * Record a Billing result.
     *
     * Only ever call this from a SUCCESSFUL query or a processed purchase
     * update. A query error, a timeout, or a disconnected Billing service must
     * not reach here at all, because passing `unlocked = false` on a failure
     * would revoke a real purchase every time the network hiccups.
     */
    suspend fun applyPlayUnlock(unlocked: Boolean) = writeMutex.withLock {
        cache.playUnlockCached = unlocked
        _state.value = resolveNow()
    }

    /**
     * Release-build test override, suppressing the `legacy_paid` source only.
     *
     * Exists because the only physical test device carries the grandfather
     * flag, so the free tier would otherwise be unobservable on real hardware.
     * It deliberately cannot hide a real Play purchase.
     */
    suspend fun setForceFree(forceFree: Boolean) = writeMutex.withLock {
        cache.forceFree = forceFree
        _state.value = resolveNow()
    }

    /** Re-read the persisted inputs, e.g. after an Auto Backup restore. */
    suspend fun refresh() = writeMutex.withLock {
        _state.value = resolveNow()
    }

    private fun resolveNow(): EntitlementState = EntitlementResolver.resolve(
        legacyPaid = prefs.isLegacyPaid,
        playUnlocked = cache.playUnlockCached,
        // Gated on BuildConfig.DEBUG at the call site, so a release build cannot
        // reach the DEBUG source at all.
        debugForceEntitled = BuildConfig.DEBUG && DEBUG_FORCE_ENTITLED,
        forceFree = cache.forceFree,
    )

    private companion object {
        /**
         * Flip locally to develop against the unlocked tier. Never true on a
         * branch, and inert in release builds regardless.
         */
        const val DEBUG_FORCE_ENTITLED = false
    }
}
