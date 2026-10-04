package com.ninelivesaudio.app.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
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
    private val reachabilityCheckGate = ReachabilityCheckGate { performServerReachabilityCheck() }

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

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            refreshIsMetered()
            _isOnline.value = true
            updateConnectionStatus()
            // Check server reachability on reconnect (deduplicated)
            launchReachabilityCheck()
        }

        override fun onLost(network: Network) {
            refreshIsMetered()
            // Check if we still have any active network
            val activeNetwork = connectivityManager.activeNetwork
            val capabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
            val stillConnected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

            if (!stillConnected) {
                _isOnline.value = false
                _isServerReachable.value = false
                updateConnectionStatus()
            }
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            // Fires when Wi-Fi is marked metered or not, too.
            refreshIsMetered()
            val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            if (_isOnline.value != hasInternet) {
                _isOnline.value = hasInternet
                updateConnectionStatus()
                if (hasInternet) {
                    launchReachabilityCheck()
                }
            }
        }
    }

    /**
     * Cancel any in-flight reachability check and start a fresh one.
     * Prevents unbounded concurrent server pings during network flaps
     * (e.g., WiFi → cellular handoff firing onAvailable + onCapabilitiesChanged).
     */
    private fun launchReachabilityCheck(): Job =
        synchronized(reachabilityJobLock) {
            reachabilityJob?.cancel()
            scope.launch { reachabilityCheckGate.run() }.also { reachabilityJob = it }
        }

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
                nextDelayMs = { nextPingDelayMs(isOnline = _isOnline.value, isServerReachable = _isServerReachable.value) },
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
        refreshIsOnlineFromSystem()

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
     * checkServerReachable() call is left as the tap's single /ping.
     */
    fun refreshIsOnlineFromSystem() {
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        _isOnline.value = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        refreshIsMetered()
        updateConnectionStatus()
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

    suspend fun checkServerReachable(): Boolean = reachabilityCheckGate.run()

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
     */
    fun setAppForeground(inForeground: Boolean) {
        appInForeground.value = inForeground
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
     * pool are often dead but not yet detected — making all API calls fail
     * until the pool cycles. Fix: evict idle connections immediately, then
     * force a server reachability check so the rest of the app knows the
     * connection state within seconds. Nothing pings while the app is in the
     * background, so this check is what refreshes a stale state.
     */
    fun onAppForegrounded() {
        // Never backgrounded yet (first foreground after cold start): there are
        // no stale connections to evict, and startMonitoring() already runs the
        // initial reachability check. Skip to avoid a pointless eviction and a
        // bogus "background=<epoch>ms" log line.
        if (backgroundedAt == 0L) return

        val elapsed = System.currentTimeMillis() - backgroundedAt
        if (elapsed < MIN_BACKGROUND_DURATION_MS) return

        Log.d(TAG, "onAppForegrounded: background=${elapsed}ms — evicting stale connections")

        // Kill stale TCP connections so the next request opens a fresh socket
        try {
            okHttpClient.connectionPool.evictAll()
        } catch (e: Exception) {
            Log.w(TAG, "onAppForegrounded: evictAll failed: ${e.message}")
        }

        // Force immediate server check (the periodic ping was off in the background)
        launchReachabilityCheck()

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
 */
internal suspend fun runForegroundPing(
    appInForeground: StateFlow<Boolean>,
    nextDelayMs: () -> Long,
    sleep: suspend (Long) -> Unit = { delay(it) },
    check: suspend () -> Unit,
) {
    appInForeground.collectLatest { inForeground ->
        if (!inForeground) return@collectLatest
        while (true) {
            sleep(nextDelayMs())
            check()
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

internal class ReachabilityCheckGate(
    private val check: suspend () -> Boolean,
) {
    private val mutex = Mutex()

    suspend fun run(): Boolean = mutex.withLock { check() }
}
