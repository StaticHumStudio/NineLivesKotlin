package com.ninelivesaudio.app.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.ninelivesaudio.app.data.remote.ApiService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monitors network connectivity and server reachability.
 * Ports MauiConnectivityService logic to native Android ConnectivityManager.
 */
@Singleton
class ConnectivityMonitor @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val apiService: ApiService,
    private val okHttpClient: OkHttpClient,
) {
    companion object {
        private const val TAG = "ConnectivityMonitor"
        /** Minimum background duration (ms) before triggering recovery on foreground. */
        private const val MIN_BACKGROUND_DURATION_MS = 5_000L
    }
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var pingJob: Job? = null

    // Guard against concurrent reachability checks during network flaps.
    // Each new request cancels any in-flight check so only the latest wins.
    private var reachabilityJob: Job? = null

    // Track when the app went to background for debouncing foreground recovery
    @Volatile private var backgroundedAt: Long = 0L

    // Emitted when the app returns from a meaningful background period
    private val _appResumedFromBackground = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val appResumedFromBackground: SharedFlow<Unit> = _appResumedFromBackground.asSharedFlow()

    // ─── State ────────────────────────────────────────────────────────────

    private val _isOnline = MutableStateFlow(false)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

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
            _isOnline.value = true
            updateConnectionStatus()
            // Check server reachability on reconnect (deduplicated)
            launchReachabilityCheck()
        }

        override fun onLost(network: Network) {
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
    private fun launchReachabilityCheck() {
        reachabilityJob?.cancel()
        reachabilityJob = scope.launch { checkServerReachable() }
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

        // Start periodic server ping (every 60 seconds)
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive) {
                delay(60_000)
                checkServerReachable()
            }
        }
    }

    fun stopMonitoring() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
        pingJob?.cancel()
        reachabilityJob?.cancel()
        reachabilityJob = null
    }

    // ─── Checks ───────────────────────────────────────────────────────────

    private fun checkCurrentConnectivity() {
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        _isOnline.value = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        updateConnectionStatus()

        // Initial server check (deduplicated)
        launchReachabilityCheck()
    }

    suspend fun checkServerReachable(): Boolean {
        if (!_isOnline.value) {
            _isServerReachable.value = false
            updateConnectionStatus()
            return false
        }

        return try {
            // Uses ApiService's lightweight auth check (with fallback debounce) to keep the 60s cadence cheap.
            val reachable = apiService.validateToken()
            _isServerReachable.value = reachable
            updateConnectionStatus()
            reachable
        } catch (_: Exception) {
            _isServerReachable.value = false
            updateConnectionStatus()
            false
        }
    }

    // ─── Sync State (updated by SyncManager) ─────────────────────────────

    fun setSyncing(syncing: Boolean) {
        _isSyncing.value = syncing
        updateConnectionStatus()
    }

    // ─── App Lifecycle (foreground / background) ─────────────────────────

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
     * connection state within seconds instead of waiting up to 60s.
     */
    fun onAppForegrounded() {
        val elapsed = System.currentTimeMillis() - backgroundedAt
        if (backgroundedAt > 0 && elapsed < MIN_BACKGROUND_DURATION_MS) return

        Log.d(TAG, "onAppForegrounded: background=${elapsed}ms — evicting stale connections")

        // Kill stale TCP connections so the next request opens a fresh socket
        try {
            okHttpClient.connectionPool.evictAll()
        } catch (e: Exception) {
            Log.w(TAG, "onAppForegrounded: evictAll failed: ${e.message}")
        }

        // Force immediate server check (don't wait for the 60s timer)
        launchReachabilityCheck()

        // Notify observers (PlaybackManager) that we're back from background
        _appResumedFromBackground.tryEmit(Unit)
    }

    // ─── Status Calculation ───────────────────────────────────────────────

    private fun updateConnectionStatus() {
        _connectionStatus.value = when {
            _isSyncing.value -> ConnectionStatus.SYNCING
            _isServerReachable.value -> ConnectionStatus.CONNECTED
            _isOnline.value -> ConnectionStatus.SERVER_UNREACHABLE
            else -> ConnectionStatus.OFFLINE
        }
    }
}
