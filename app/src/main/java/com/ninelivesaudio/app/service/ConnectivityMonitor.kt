package com.ninelivesaudio.app.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.ninelivesaudio.app.data.remote.ApiService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var pingJob: Job? = null

    // Guard against concurrent reachability checks during network flaps.
    // Each new request cancels any in-flight check so only the latest wins.
    private var reachabilityJob: Job? = null

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
