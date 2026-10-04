package com.ninelivesaudio.app.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monitors network connectivity and server reachability.
 * Ports MauiConnectivityService logic to native Android ConnectivityManager.
 */
@Singleton
class ConnectivityMonitor @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val settingsManager: SettingsManager,
) {
    companion object {
        private const val TAG = "ConnectivityMonitor"
        /** Minimum background duration (ms) before triggering recovery on foreground. */
        private const val MIN_BACKGROUND_DURATION_MS = 5_000L
    }

    // A clone of the app client with a short total-call timeout, used only for the
    // reachability probe so it fails fast instead of waiting out the 30s sync timeout.
    // Each probe sets its own call budget on top (see probeServerWithRetry).
    private val probeClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var pingJob: Job? = null

    // Guard background reachability requests during network flaps. Direct and
    // background checks also share reachabilityCheckGate, so probes cannot race.
    private var reachabilityJob: Job? = null
    private val reachabilityJobLock = Any()
    private val reachabilityCheckGate = ReachabilityCheckGate(
        nowMs = { SystemClock.elapsedRealtime() },
        reuseWindowMs = PROBE_REUSE_WINDOW_MS,
    ) { performServerReachabilityCheck() }

    // Track when the app went to background for debouncing foreground recovery
    @Volatile private var backgroundedAt: Long = 0L

    // Set from the process lifecycle (NineLivesApp). The periodic ping only
    // runs while this is true.
    private val appInForeground = MutableStateFlow(false)

    // Emitted when the app returns from a meaningful background period
    private val _appResumedFromBackground = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val appResumedFromBackground: SharedFlow<Unit> = _appResumedFromBackground.asSharedFlow()

    // ─── State ────────────────────────────────────────────────────────────

    private val _isOnline = MutableStateFlow(false)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    // Unknown counts as metered, so nothing big waits on a guess of free data.
    private val _isMetered = MutableStateFlow(true)

    /**
     * Whether the active network may cost the user money (cell data, a
     * hotspot, Wi-Fi marked metered). Background full library downloads wait
     * while this is true. Re-read from the OS on every network callback.
     */
    val isMetered: StateFlow<Boolean> = _isMetered.asStateFlow()

    private val _isServerReachable = MutableStateFlow(false)
    val isServerReachable: StateFlow<Boolean> = _isServerReachable.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    // ─── Connection Status ────────────────────────────────────────────────

    enum class ConnectionStatus {
        CONNECTED, SYNCING, SERVER_UNREACHABLE, OFFLINE
    }

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.OFFLINE)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    // ─── Network Callback ─────────────────────────────────────────────────

    // Every network the callbacks have reported, and whether it carries
    // internet. The online flag is re-derived from all of them plus the OS
    // default network, so one onLost (often for the old network after the
    // new one is already up) cannot mark the app offline on its own.
    private val knownNetworks = ConcurrentHashMap<Network, Boolean>()

    // Bumped whenever the path to the server may have changed: online
    // flipped, a new network appeared, a known one was lost, or a caller
    // asked for a fresh probe. A finished probe's answer is shared only
    // within one generation (see ReachabilityCheckGate).
    private val networkGeneration = AtomicLong(0L)

    // True once startMonitoring ran, so a foreground entry before settings
    // load does not probe a blank server URL.
    @Volatile private var monitoring = false

    // Process-level background start (SystemClock.elapsedRealtime), 0 before
    // the first trip to the background.
    @Volatile private var processBackgroundedAt: Long = 0L

    // Foreground-only OS re-reads while the network still reads as down
    // right after the app comes back (see FOREGROUND_SETTLE_REREADS_MS).
    private var settleJob: Job? = null
    private val settleJobLock = Any()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val isNew = knownNetworks.put(network, true) == null
            if (isNew) networkGeneration.incrementAndGet()
            reevaluateNetwork()
            // Check server reachability on reconnect (deduplicated)
            launchReachabilityCheck()
        }

        override fun onLost(network: Network) {
            if (knownNetworks.remove(network) != null) networkGeneration.incrementAndGet()
            // Re-derive from every network still known plus the OS default.
            // A backgrounded app can be network-blocked, so the OS default
            // reads null here even with Wi-Fi up. Coming back to the
            // foreground re-reads it (reevaluateOnForeground).
            reevaluateNetwork()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            // Fires when Wi-Fi is marked metered or not, too.
            knownNetworks[network] = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val (wasOnline, online) = reevaluateNetwork()
            if (online && !wasOnline) launchReachabilityCheck()
        }

        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            // Android blocks a backgrounded app's network and unblocks it when
            // the app returns. The unblock is the moment the OS default
            // network becomes readable again.
            if (blocked) return
            val (wasOnline, online) = reevaluateNetwork()
            if (online && (!wasOnline || !_isServerReachable.value)) {
                launchReachabilityCheck()
            }
        }
    }

    /**
     * Re-derives [isOnline] from the OS default network and every network the
     * callbacks know about, and returns (online before, online now). Going
     * offline also clears server reachability. No network request.
     */
    private fun reevaluateNetwork(): Pair<Boolean, Boolean> = synchronized(knownNetworks) {
        val active = try {
            connectivityManager.activeNetwork
        } catch (_: Exception) {
            null
        }
        val activeHasInternet = active?.let { hasInternet(it) } == true
        // The default network is known too, so its replayed onAvailable at
        // registration does not count as a new network.
        if (active != null && activeHasInternet) knownNetworks[active] = true
        val online = networkStateSaysOnline(
            defaultHasInternet = activeHasInternet,
            knownNetworksHaveInternet = knownNetworks.values,
        )
        val wasOnline = _isOnline.value
        _isOnline.value = online
        if (!online) _isServerReachable.value = false
        if (online != wasOnline) networkGeneration.incrementAndGet()
        refreshIsMetered()
        updateConnectionStatus()
        wasOnline to online
    }

    private fun hasInternet(network: Network): Boolean = try {
        connectivityManager.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: Exception) {
        false
    }

    /**
     * Drops networks the OS no longer knows (an onLost that never arrived
     * while the app was frozen), so a stale entry cannot keep the app
     * "online" with no network at all.
     */
    private fun pruneGoneNetworks() {
        val gone = knownNetworks.keys.filter { network ->
            try {
                connectivityManager.getNetworkCapabilities(network) == null
            } catch (_: Exception) {
                true
            }
        }
        gone.forEach { knownNetworks.remove(it) }
    }

    /**
     * Start a reachability check, or join the one already running for the
     * same network generation. A check from an older generation is cancelled
     * (its answer describes a path that may be gone). [fresh] starts a new
     * generation first, for callers that must not reuse an earlier answer.
     * Cold start used to send 3 to 4 /ping in about 1.5 seconds because each
     * caller cancelled the one before and pinged again.
     */
    private fun launchReachabilityCheck(fresh: Boolean = false): Job =
        synchronized(reachabilityJobLock) {
            if (fresh) networkGeneration.incrementAndGet()
            val generation = networkGeneration.get()
            val running = reachabilityJob
            if (running != null && running.isActive && reachabilityJobGeneration == generation) {
                return@synchronized running
            }
            running?.cancel()
            reachabilityJobGeneration = generation
            val key = reuseKey()
            scope.launch { reachabilityCheckGate.run(key) }.also { reachabilityJob = it }
        }

    // The generation the running reachabilityJob belongs to. Guarded by reachabilityJobLock.
    private var reachabilityJobGeneration = -1L

    private fun reuseKey(): ProbeReuseKey = ProbeReuseKey(
        generation = networkGeneration.get(),
        serverUrl = settingsManager.currentSettings.serverUrl.trim(),
    )

    fun requestReachabilityCheck() {
        launchReachabilityCheck()
    }

    // ─── Start / Stop ─────────────────────────────────────────────────────

    fun startMonitoring() {
        // Register for network callbacks
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            connectivityManager.registerNetworkCallback(request, networkCallback)
        } catch (_: Exception) {
            // Already registered or other error
        }

        // Initial state check
        monitoring = true
        checkCurrentConnectivity()

        // Start the periodic server ping, foreground only: every 60 seconds,
        // or 15 while the network is up and the server is not answering, so a
        // server that comes back is noticed soon. collectLatest cancels the
        // loop the moment the app leaves the foreground. In the background
        // the network callbacks still re-check on every network change, and
        // the playback progress push marks the server reachable when it lands
        // (see reportServerAnswered), so nothing needs a timer there.
        pingJob?.cancel()
        pingJob = scope.launch {
            runForegroundPing(
                appInForeground = appInForeground,
                // Restarts the wait whenever the status changes the interval,
                // so a server that just went unreachable is re-probed 15
                // seconds later, not at the end of the 60 already running.
                intervalMs = combine(_isOnline, _isServerReachable) { online, reachable ->
                    nextPingDelayMs(isOnline = online, isServerReachable = reachable)
                },
                // Route through launchReachabilityCheck so the periodic ping
                // shares the single-flight cancellation with the callback- and
                // foreground-driven checks. Calling checkServerReachable()
                // directly let two checks race and the slower (stale) one win
                // the last write to _isServerReachable.
                check = { launchReachabilityCheck().join() },
            )
        }
    }

    fun stopMonitoring() {
        monitoring = false
        cancelSettleRereads()
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
        pingJob?.cancel()
        synchronized(reachabilityJobLock) {
            reachabilityJob?.cancel()
            reachabilityJob = null
        }
    }

    // ─── Checks ───────────────────────────────────────────────────────────

    private fun checkCurrentConnectivity() {
        // No forced new generation here: the default network's onAvailable,
        // replayed at registration, may already have started this probe.
        pruneGoneNetworks()
        reevaluateNetwork()

        // Initial server check (deduplicated)
        launchReachabilityCheck()
    }

    /**
     * Re-reads the OS's current network capabilities directly into [isOnline],
     * bypassing NetworkCallback. The callback normally keeps isOnline current,
     * but it can lag right after connectivity returns, or on some OEM
     * power-management skins never fire at all — leaving isOnline stuck false
     * with a real network already up.
     *
     * Also called directly by the Home reconnect tap (no network request here,
     * just re-reading the OS state) so [SyncManager.syncNow]'s own shouldRunSync
     * pre-check sees a fresh flag instead of the stale one, and its own
     * checkServerReachable() call is left as the tap's single /ping. It
     * starts a new network generation, so that /ping is never answered from
     * an earlier probe's shared result.
     */
    fun refreshIsOnlineFromSystem() {
        pruneGoneNetworks()
        reevaluateNetwork()
        // An explicit re-read (the reconnect tap, a mode switch) wants a
        // probe of its own, not one shared from just before.
        networkGeneration.incrementAndGet()
    }

    /** Re-reads the default network's metered state from the OS. */
    fun refreshIsMetered(): Boolean {
        val metered = try {
            connectivityManager.isActiveNetworkMetered
        } catch (_: Exception) {
            true
        }
        _isMetered.value = metered
        return metered
    }

    /**
     * The server's reachability, from a fresh /ping or from one that finished
     * within [PROBE_REUSE_WINDOW_MS] on the same network and server.
     */
    suspend fun checkServerReachable(): Boolean = reachabilityCheckGate.run(reuseKey())

    suspend fun probeServerReachable(): Boolean = checkServerReachable()

    private suspend fun performServerReachabilityCheck(): Boolean {
        if (!_isOnline.value) {
            _isServerReachable.value = false
            updateConnectionStatus()
            return false
        }
        val serverUrl = settingsManager.currentSettings.serverUrl.trim()
        if (serverUrl.isBlank()) {
            _isServerReachable.value = false
            updateConnectionStatus()
            return false
        }
        val pingUrl = "${serverUrl.trimEnd('/')}/ping"
        val reachable = probeServerWithRetry(
            wasReachable = _isServerReachable.value,
            stillOnline = { _isOnline.value },
            probe = { budgetMs -> pingOnce(pingUrl, budgetMs) },
        )
        _isServerReachable.value = reachable
        updateConnectionStatus()
        return reachable
    }

    private suspend fun pingOnce(pingUrl: String, budgetMs: Long): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(pingUrl).build()
                val call = probeClient.newCall(request)
                call.timeout().timeout(budgetMs, TimeUnit.MILLISECONDS)
                call.execute().use { response -> pingStatusMeansReachable(response.code) }
            } catch (_: Exception) {
                false
            }
        }

    /**
     * A real request to the server just succeeded (a playback progress push).
     * That proves reachability better than a /ping, and with no ping in the
     * background it is how a server marked unreachable comes back while the
     * app is not visible, which in turn flushes the offline progress queue
     * and lets Android Auto stream again. A failed request proves nothing,
     * so there is no counterpart: only a probe marks the server unreachable.
     */
    fun reportServerAnswered() {
        if (!serverAnswerMarksReachable(_isOnline.value, _isServerReachable.value)) return
        _isServerReachable.value = true
        updateConnectionStatus()
    }

    // ─── Sync State (updated by SyncManager) ─────────────────────────────

    fun setSyncing(syncing: Boolean) {
        _isSyncing.value = syncing
        updateConnectionStatus()
    }

    // ─── App Lifecycle (foreground / background) ─────────────────────────

    /**
     * The app came to the foreground (true) or left it (false), from the
     * process lifecycle so it covers every activity together. The periodic
     * ping runs only in the foreground.
     *
     * Coming back re-reads the real network state first. Android blocks a
     * backgrounded app's network, so a network change while it was away can
     * leave [isOnline] false with Wi-Fi fine, and nothing else would ever
     * re-read it (no ping runs in the background, and the ping loop cannot
     * get past an offline flag). Call this before anything that reads
     * [isOnline] on entry, such as SyncManager's entry check.
     */
    fun setAppForeground(inForeground: Boolean) {
        if (inForeground) {
            val backgroundedAt = processBackgroundedAt
            val backgroundForMs =
                if (backgroundedAt == 0L) null else SystemClock.elapsedRealtime() - backgroundedAt
            appInForeground.value = true
            reevaluateOnForeground(backgroundForMs)
        } else {
            processBackgroundedAt = SystemClock.elapsedRealtime()
            appInForeground.value = false
            cancelSettleRereads()
        }
    }

    /**
     * Foreground entry: re-derive connectivity from the OS, probe the server
     * when [foregroundEntryProbes] says so, and keep re-reading for a few
     * seconds while the OS still reports no network (the unblock can land
     * just after the app is visible).
     */
    private fun reevaluateOnForeground(backgroundForMs: Long?) {
        if (!monitoring) return
        pruneGoneNetworks()
        val (wasOnline, online) = reevaluateNetwork()
        Log.d(TAG, "foreground: background=${backgroundForMs}ms online $wasOnline -> $online")
        if (foregroundEntryProbes(wasOnline, online, _isServerReachable.value, backgroundForMs)) {
            launchReachabilityCheck(fresh = true)
        }
        if (!online) startSettleRereads()
    }

    private fun startSettleRereads() {
        synchronized(settleJobLock) {
            settleJob?.cancel()
            settleJob = scope.launch {
                rereadUntilOnline(FOREGROUND_SETTLE_REREADS_MS) {
                    if (!appInForeground.value) return@rereadUntilOnline true
                    val (wasOnline, online) = reevaluateNetwork()
                    if (online && !wasOnline) {
                        Log.d(TAG, "foreground settle: network readable again")
                        launchReachabilityCheck(fresh = true)
                    }
                    online
                }
            }
        }
    }

    private fun cancelSettleRereads() {
        synchronized(settleJobLock) {
            settleJob?.cancel()
            settleJob = null
        }
    }

    /**
     * Called when the app moves to the background (Activity.onStop).
     * Records the timestamp for debouncing foreground recovery.
     */
    fun onAppBackgrounded() {
        backgroundedAt = System.currentTimeMillis()
    }

    /**
     * Called when the app returns to the foreground (Activity.onStart).
     *
     * After device sleep or extended background, TCP connections in OkHttp's
     * pool are often dead but not yet detected, making all API calls fail
     * until the pool cycles. Fix: evict idle connections immediately. The
     * reachability check that follows runs from [setAppForeground], which
     * the process lifecycle calls just after this, once the network state
     * has been re-read.
     */
    fun onAppForegrounded() {
        // Never backgrounded yet (first foreground after cold start): there are
        // no stale connections to evict, and startMonitoring() already runs the
        // initial reachability check. Skip to avoid a pointless eviction and a
        // bogus "background=<epoch>ms" log line.
        if (backgroundedAt == 0L) return

        val elapsed = System.currentTimeMillis() - backgroundedAt
        if (elapsed < MIN_BACKGROUND_DURATION_MS) return

        Log.d(TAG, "onAppForegrounded: background=${elapsed}ms, evicting stale connections")

        // Kill stale TCP connections so the next request opens a fresh socket
        try {
            okHttpClient.connectionPool.evictAll()
        } catch (e: Exception) {
            Log.w(TAG, "onAppForegrounded: evictAll failed: ${e.message}")
        }

        // Notify observers (PlaybackManager) that we're back from background
        _appResumedFromBackground.tryEmit(Unit)
    }

    // ─── Status Calculation ───────────────────────────────────────────────

    private fun updateConnectionStatus() {
        _connectionStatus.value = computeConnectionStatus(
            isOnline = _isOnline.value,
            isSyncing = _isSyncing.value,
            isServerReachable = _isServerReachable.value,
        )
    }
}

/**
 * Pure status resolution. OFFLINE takes precedence over everything: with no
 * network there is nothing to sync and nothing to reach, so a lingering sync
 * flag (a coroutine still timing out on a dead socket) must not keep the UI
 * showing "Syncing" after airplane mode is on.
 */
internal fun computeConnectionStatus(
    isOnline: Boolean,
    isSyncing: Boolean,
    isServerReachable: Boolean,
): ConnectivityMonitor.ConnectionStatus = when {
    !isOnline -> ConnectivityMonitor.ConnectionStatus.OFFLINE
    isSyncing -> ConnectivityMonitor.ConnectionStatus.SYNCING
    isServerReachable -> ConnectivityMonitor.ConnectionStatus.CONNECTED
    else -> ConnectivityMonitor.ConnectionStatus.SERVER_UNREACHABLE
}

/** First probe budget. Short so a gone server shows as unreachable quickly. */
internal const val PROBE_TIMEOUT_MS = 5_000L

/** Wait before the confirming probe, so a busy moment can pass. */
internal const val PROBE_RETRY_DELAY_MS = 3_000L

/** Budget for the confirming probe. Longer, for a busy Pi or a slow VPN. */
internal const val PROBE_RETRY_TIMEOUT_MS = 10_000L

/** The periodic ping while the server answers (or the network is down). */
internal const val PING_INTERVAL_MS = 60_000L

/** The periodic ping while the network is up and the server is not answering. */
internal const val PING_RETRY_INTERVAL_MS = 15_000L

/**
 * Any answer from /ping below 500 means Audiobookshelf itself is up. A 5xx is
 * a reverse proxy (or tunnel) answering for a server that is down, which used
 * to read as Connected because any response counted.
 */
internal fun pingStatusMeansReachable(code: Int): Boolean = code in 100..499

/**
 * One /ping, and when it fails while the server was reachable and the OS
 * still reports a network, one more after [retryDelayMs] with a longer
 * budget before the server is called unreachable.
 *
 * Calling it unreachable switches a streaming user's Library to Downloaded
 * only, so one slow answer from a busy Pi or a VPN must not do that. A server
 * already marked unreachable gets no retry: a failure there changes nothing,
 * and a success flips it back on the first try.
 */
internal suspend fun probeServerWithRetry(
    wasReachable: Boolean,
    stillOnline: () -> Boolean,
    probe: suspend (budgetMs: Long) -> Boolean,
    sleep: suspend (Long) -> Unit = { delay(it) },
    firstBudgetMs: Long = PROBE_TIMEOUT_MS,
    retryDelayMs: Long = PROBE_RETRY_DELAY_MS,
    retryBudgetMs: Long = PROBE_RETRY_TIMEOUT_MS,
): Boolean {
    if (probe(firstBudgetMs)) return true
    if (!wasReachable || !stillOnline()) return false
    sleep(retryDelayMs)
    if (!stillOnline()) return false
    return probe(retryBudgetMs)
}

/**
 * The periodic ping loop. It runs only while [appInForeground] is true and
 * stops the moment it turns false, so nothing pings in the background.
 *
 * Each wait is [intervalMs]'s latest value, and a new value restarts the
 * wait in progress. The loop used to pick its delay before sleeping, so a
 * server that went unreachable during a 60 second wait was first re-probed
 * up to 60 seconds later instead of 15.
 */
internal suspend fun runForegroundPing(
    appInForeground: StateFlow<Boolean>,
    intervalMs: Flow<Long>,
    sleep: suspend (Long) -> Unit = { delay(it) },
    check: suspend () -> Unit,
) {
    appInForeground.collectLatest { inForeground ->
        if (!inForeground) return@collectLatest
        intervalMs.distinctUntilChanged().collectLatest { interval ->
            while (true) {
                sleep(interval)
                check()
            }
        }
    }
}

/**
 * A successful server request marks the server reachable only while the OS
 * reports a network, and only when it is not already marked reachable.
 */
internal fun serverAnswerMarksReachable(isOnline: Boolean, isServerReachable: Boolean): Boolean =
    isOnline && !isServerReachable

/** How long the periodic ping waits before its next probe. */
internal fun nextPingDelayMs(isOnline: Boolean, isServerReachable: Boolean): Long =
    if (isOnline && !isServerReachable) PING_RETRY_INTERVAL_MS else PING_INTERVAL_MS

/** Which probe answers can be shared: same network generation, same server. */
internal data class ProbeReuseKey(val generation: Long, val serverUrl: String)

/**
 * How long a finished probe's answer is shared with callers asking about the
 * same network and server. Cold start has the monitor, the sync's entry
 * check, the Library and playback all asking within a second or two.
 */
internal const val PROBE_REUSE_WINDOW_MS = 3_000L

/**
 * Whether a probe that finished at [finishedAtMs] under [previousKey] answers
 * a caller asking under [key] at [nowMs]. A null key never shares, and a
 * clock reading before the finish (or the window passing) means probe again.
 */
internal fun canReuseProbe(
    previousKey: Any?,
    key: Any?,
    finishedAtMs: Long,
    nowMs: Long,
    windowMs: Long,
): Boolean {
    if (key == null || previousKey != key) return false
    val age = nowMs - finishedAtMs
    return age in 0 until windowMs
}

/**
 * Runs reachability checks one at a time. A caller whose [run] key matches
 * a check that finished under [reuseWindowMs] ago (including one it waited
 * on) gets that answer instead of a new /ping.
 */
internal class ReachabilityCheckGate(
    private val nowMs: () -> Long = { 0L },
    private val reuseWindowMs: Long = 0L,
    private val check: suspend () -> Boolean,
) {
    private class Finished(val key: Any?, val atMs: Long, val reachable: Boolean)

    private val mutex = Mutex()
    private var last: Finished? = null // guarded by mutex

    suspend fun run(key: Any? = null): Boolean = mutex.withLock {
        val previous = last
        if (previous != null && canReuseProbe(previous.key, key, previous.atMs, nowMs(), reuseWindowMs)) {
            return@withLock previous.reachable
        }
        val reachable = check()
        last = Finished(key, nowMs(), reachable)
        reachable
    }
}

/**
 * Whether the app has a network, from the OS default network and every
 * network the callbacks still know about. A single onLost used to decide this
 * from the default network alone, which reads null for a backgrounded app
 * Android has network-blocked, so the old Wi-Fi's onLost marked the app
 * offline with the new Wi-Fi already up. INTERNET, not VALIDATED: a home
 * server on a LAN with no route to the internet never validates.
 */
internal fun networkStateSaysOnline(
    defaultHasInternet: Boolean,
    knownNetworksHaveInternet: Collection<Boolean>,
): Boolean = defaultHasInternet || knownNetworksHaveInternet.any { it }

/** Minimum background time before a foreground entry re-probes a server that was answering. */
internal const val FOREGROUND_REPROBE_AFTER_MS = 5_000L

/**
 * Whether a foreground entry probes the server, after the network state has
 * just been re-read from the OS.
 *
 * - No network: nothing to probe.
 * - The re-read found a network the stale state had missed: probe, this is
 *   the stuck "Offline" coming unstuck.
 * - The server is not marked reachable: probe, so the screen stops lying.
 * - First entry after a cold start ([backgroundForMs] null): startMonitoring
 *   already probes.
 * - Otherwise only after a real trip to the background, not a quick flip.
 */
internal fun foregroundEntryProbes(
    wasOnline: Boolean,
    isOnline: Boolean,
    isServerReachable: Boolean,
    backgroundForMs: Long?,
): Boolean = when {
    !isOnline -> false
    !wasOnline -> true
    !isServerReachable -> true
    backgroundForMs == null -> false
    else -> backgroundForMs >= FOREGROUND_REPROBE_AFTER_MS
}

/**
 * When a foreground entry still reads no network, how long after it to read
 * the OS state again. Android lifts its background network block a moment
 * after the app is visible. Reads only (no request), foreground only, and it
 * stops at the first one that finds a network.
 */
internal val FOREGROUND_SETTLE_REREADS_MS = listOf(1_000L, 2_000L, 4_000L)

/**
 * Waits out each delay in [delaysMs] and calls [reread], stopping as soon as
 * it returns true. Returns how many rereads ran.
 */
internal suspend fun rereadUntilOnline(
    delaysMs: List<Long>,
    sleep: suspend (Long) -> Unit = { delay(it) },
    reread: () -> Boolean,
): Int {
    var reads = 0
    for (wait in delaysMs) {
        sleep(wait)
        reads += 1
        if (reread()) break
    }
    return reads
}
