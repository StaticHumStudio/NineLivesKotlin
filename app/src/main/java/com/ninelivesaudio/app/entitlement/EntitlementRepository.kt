package com.ninelivesaudio.app.entitlement

import com.ninelivesaudio.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

internal interface DurableEntitlementStore {
    val legacyPaid: Boolean
    val trialStartedAtEpochMs: Long?
    val trialConsumed: Boolean
    fun consumeTrial(startedAtEpochMs: Long): Boolean

    /**
     * Advance the persisted trial clock high-water mark to at least
     * [nowEpochMs] and return the resulting mark, or null if no trial has ever
     * started.
     *
     * Only ever moves forward. This is what makes rolling the device clock
     * back after a trial has already been observed to expire a no-op: the mark
     * keeps standing at the latest time this install ever legitimately saw.
     */
    fun advanceTrialWatermark(nowEpochMs: Long): Long?
}

internal interface PlayEntitlementCache {
    var playUnlockCached: Boolean
    var forceFree: Boolean
}

internal fun interface TrialReminderScheduler {
    fun schedule(startedAtEpochMs: Long)
    fun cancel() = Unit
}

internal data class TrialExpiryRefreshPlan(
    val targetAtEpochMs: Long,
    val initialDelayMs: Long,
) {
    companion object {
        private const val REFRESH_AFTER_EXPIRY_MS = 1_000L

        fun forState(
            state: EntitlementState,
            nowEpochMs: Long,
        ): TrialExpiryRefreshPlan? {
            if (state.source != EntitlementSource.TRIAL || !state.isUnlocked) return null
            val endsAt = state.trialEndsAtEpochMs ?: return null
            if (nowEpochMs < 0 || endsAt <= nowEpochMs) return null

            val target = if (endsAt > Long.MAX_VALUE - REFRESH_AFTER_EXPIRY_MS) {
                Long.MAX_VALUE
            } else {
                endsAt + REFRESH_AFTER_EXPIRY_MS
            }
            return TrialExpiryRefreshPlan(
                targetAtEpochMs = target,
                initialDelayMs = target - nowEpochMs,
            )
        }
    }
}

internal interface TrialExpiryRefreshScheduler {
    fun replace(
        plan: TrialExpiryRefreshPlan?,
        refresh: suspend () -> Unit,
    )
}

internal class CoroutineTrialExpiryRefreshScheduler(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val wait: suspend (Long) -> Unit = { delay(it) },
) : TrialExpiryRefreshScheduler {
    private var pendingRefresh: Job? = null

    override fun replace(
        plan: TrialExpiryRefreshPlan?,
        refresh: suspend () -> Unit,
    ) {
        pendingRefresh?.cancel()
        pendingRefresh = plan?.let {
            scope.launch {
                wait(it.initialDelayMs)
                refresh()
            }
        }
    }
}

/**
 * The single source of truth for entitlement. Every gate observes [state] and
 * nothing anywhere queries billing directly.
 *
 * Billing writes through [applyPlayUnlock], while an explicit trial start writes
 * through [startTrial]. Both paths resolve here before any gate sees the result.
 */
@Singleton
class EntitlementRepository internal constructor(
    private val prefs: DurableEntitlementStore,
    private val cache: PlayEntitlementCache,
    private val reminderScheduler: TrialReminderScheduler,
    private val nowEpochMs: () -> Long,
    private val expiryRefreshScheduler: TrialExpiryRefreshScheduler =
        CoroutineTrialExpiryRefreshScheduler(),
) {
    @Inject
    internal constructor(
        prefs: EntitlementPrefs,
        cache: EntitlementCachePrefs,
        reminderScheduler: WorkManagerTrialReminderScheduler,
    ) : this(
        prefs = prefs,
        cache = cache,
        reminderScheduler = reminderScheduler,
        nowEpochMs = System::currentTimeMillis,
    )

    private val _state = MutableStateFlow(resolveNow())
    val state: StateFlow<EntitlementState> = _state.asStateFlow()

    init {
        rescheduleExpiryRefresh(_state.value, nowEpochMs())
    }

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
        resolveAndPublish(nowEpochMs())
        if (unlocked) {
            // The Play grant is already durable. Reminder cleanup is best-effort
            // and must never turn a successful purchase into a failed one.
            runCatching { reminderScheduler.cancel() }
        }
    }

    /** Persist and grant the one-time trial after an explicit user action. */
    suspend fun startTrial(): Boolean = writeMutex.withLock {
        val startedAt = nowEpochMs()
        val before = resolveAt(startedAt)
        if (before.isUnlocked || !before.trialOfferAvailable) return@withLock false
        if (TrialPolicy.evaluate(startedAt, startedAt) == null) return@withLock false

        if (!prefs.consumeTrial(startedAt)) {
            resolveAndPublish(startedAt)
            return@withLock false
        }

        resolveAndPublish(startedAt)
        if (_state.value.source != EntitlementSource.TRIAL) return@withLock false

        // The trial is already durable. A notification failure must never revoke it.
        runCatching { reminderScheduler.schedule(startedAt) }
        true
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
        resolveAndPublish(nowEpochMs())
    }

    /** Re-read the persisted inputs, e.g. after an Auto Backup restore. */
    suspend fun refresh() = writeMutex.withLock {
        resolveAndPublish(nowEpochMs())
    }

    private fun resolveNow(): EntitlementState = resolveAt(nowEpochMs())

    private fun resolveAndPublish(now: Long) {
        val resolved = resolveAt(now)
        _state.value = resolved
        rescheduleExpiryRefresh(resolved, now)
    }

    private fun rescheduleExpiryRefresh(state: EntitlementState, now: Long) {
        expiryRefreshScheduler.replace(
            plan = TrialExpiryRefreshPlan.forState(state, now),
            refresh = {
                refresh()
            },
        )
    }

    private fun resolveAt(now: Long): EntitlementState = EntitlementResolver.resolve(
        legacyPaid = prefs.legacyPaid,
        playUnlocked = cache.playUnlockCached,
        // Gated on BuildConfig.DEBUG at the call site, so a release build cannot
        // reach the DEBUG source at all.
        debugForceEntitled = BuildConfig.DEBUG && DEBUG_FORCE_ENTITLED,
        forceFree = cache.forceFree,
        trialStartedAtEpochMs = prefs.trialStartedAtEpochMs,
        trialConsumed = prefs.trialConsumed,
        nowEpochMs = now,
        // Advancing here, on every resolution, is what feeds TrialPolicy a mark
        // that has actually seen this device's clock. A call site that only
        // read the mark without advancing it would let a trial evaluated once
        // right after a rollback, then never again, dodge detection forever.
        trialLatestSeenEpochMs = prefs.advanceTrialWatermark(now),
    )

    private companion object {
        /**
         * Flip locally to develop against the unlocked tier. Never true on a
         * branch, and inert in release builds regardless.
         */
        const val DEBUG_FORCE_ENTITLED = false
    }
}
