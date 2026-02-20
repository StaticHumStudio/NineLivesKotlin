package com.ninelivesaudio.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninelivesaudio.app.data.local.dao.AudioBookDao
import com.ninelivesaudio.app.data.local.dao.LibraryDao
import com.ninelivesaudio.app.data.remote.ApiService
import com.ninelivesaudio.app.service.ConnectivityMonitor
import com.ninelivesaudio.app.service.ConnectivityMonitor.ConnectionStatus
import com.ninelivesaudio.app.service.PlaybackManager
import com.ninelivesaudio.app.service.SettingsManager
import com.ninelivesaudio.app.service.SyncManager
import com.ninelivesaudio.app.settings.unhinged.UnhingedSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsManager: SettingsManager,
    private val apiService: ApiService,
    private val connectivityMonitor: ConnectivityMonitor,
    private val audioBookDao: AudioBookDao,
    private val libraryDao: LibraryDao,
    private val unhingedRepository: UnhingedSettingsRepository,
    private val syncManager: SyncManager,
    private val playbackManager: PlaybackManager,
) : ViewModel() {

    // ─── UI State ─────────────────────────────────────────────────────────

    data class UiState(
        // Connection
        val serverUrl: String = "",
        val username: String = "",
        val password: String = "",
        val isConnected: Boolean = false,
        val isConnecting: Boolean = false,
        val connectionStatusText: String = "Not connected",
        val connectionStatus: ConnectionStatus = ConnectionStatus.OFFLINE,

        // Security
        val allowSelfSignedCertificates: Boolean = false,

        // Sync
        val isSyncing: Boolean = false,

        // Messages
        val errorMessage: String? = null,
        val successMessage: String? = null,

        // Diagnostics
        val appVersion: String = "1.0.0",
        val settingsFilePath: String = "",

        // Archive Configuration
        val sessionCount: Int = 0,
        val anomaliesEnabled: Boolean = true,
        val whispersEnabled: Boolean = true,
        val reduceMotionRequested: Boolean = false,

        // Equalizer
        val eqEnabled: Boolean = false,
        val eqBandGains: List<Int> = List(9) { 0 },
        val eqBandFrequencies: List<Int> = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000),
        val eqBandRange: Pair<Int, Int> = Pair(-1500, 1500),
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // ─── Init ─────────────────────────────────────────────────────────────

    init {
        // Observe connectivity status
        viewModelScope.launch {
            connectivityMonitor.connectionStatus.collect { status ->
                _uiState.update { it.copy(connectionStatus = status) }
            }
        }

        // App version — always Archive Beneath
        viewModelScope.launch {
            settingsManager.settings.collect { settings ->
                _uiState.update {
                    it.copy(appVersion = getAppVersion())
                }
            }
        }

        // Observe unhinged settings for session count + feature preferences
        viewModelScope.launch {
            unhingedRepository.settingsFlow.collect { unhingedSettings ->
                _uiState.update {
                    it.copy(
                        sessionCount = unhingedSettings.sessionCount,
                        anomaliesEnabled = unhingedSettings.anomaliesEnabled,
                        whispersEnabled = unhingedSettings.whispersEnabled,
                        reduceMotionRequested = unhingedSettings.reduceMotionRequested,
                    )
                }
            }
        }

        // Observe sync state
        viewModelScope.launch {
            syncManager.isSyncing.collect { syncing ->
                _uiState.update { it.copy(isSyncing = syncing) }
            }
        }

        // Observe EQ state
        viewModelScope.launch {
            playbackManager.eqEnabled.collect { enabled ->
                _uiState.update { it.copy(eqEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            playbackManager.eqBandGains.collect { gains ->
                _uiState.update {
                    it.copy(
                        eqBandGains = gains,
                        eqBandFrequencies = playbackManager.getEqBandFrequencies(),
                        eqBandRange = playbackManager.getEqBandRange(),
                    )
                }
            }
        }

        // Load settings on init
        viewModelScope.launch {
            initialize()
        }
    }

    private suspend fun initialize() {
        settingsManager.loadSettings()
        val settings = settingsManager.currentSettings

        _uiState.update { state ->
            state.copy(
                serverUrl = settings.serverUrl,
                username = settings.username,
                allowSelfSignedCertificates = settings.allowSelfSignedCertificates,
                settingsFilePath = settingsManager.settingsFilePath,
                appVersion = getAppVersion(),
            )
        }

        // Check if already connected by validating stored token
        val hasToken = settingsManager.getAuthToken()?.isNotEmpty() == true
        if (hasToken) {
            try {
                val valid = apiService.validateToken()
                _uiState.update {
                    it.copy(
                        isConnected = valid,
                        connectionStatusText = if (valid) "Connected to ${settings.serverUrl}" else "Session expired — please reconnect",
                    )
                }
                if (!valid) {
                    apiService.logout()
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isConnected = false,
                        connectionStatusText = "Connection check failed: ${e.message}",
                    )
                }
            }
        }
    }

    // ─── User Actions ─────────────────────────────────────────────────────

    fun onServerUrlChanged(value: String) {
        _uiState.update { it.copy(serverUrl = value.trim(), errorMessage = null) }
    }

    fun onUsernameChanged(value: String) {
        _uiState.update { it.copy(username = value.trim(), errorMessage = null) }
    }

    fun onPasswordChanged(value: String) {
        _uiState.update { it.copy(password = value, errorMessage = null) }
    }

    fun onAllowSelfSignedChanged(value: Boolean) {
        _uiState.update { it.copy(allowSelfSignedCertificates = value) }
        viewModelScope.launch {
            settingsManager.updateSettings { it.copy(allowSelfSignedCertificates = value) }
        }
    }

    fun connect() {
        val state = _uiState.value

        if (state.serverUrl.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Please enter a server URL") }
            return
        }
        if (state.username.isBlank() || state.password.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Please enter username and password") }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isConnecting = true,
                    errorMessage = null,
                    successMessage = null,
                    connectionStatusText = "Connecting...",
                )
            }

            try {
                val success = apiService.login(state.serverUrl, state.username, state.password)

                if (success) {
                    _uiState.update {
                        it.copy(
                            isConnected = true,
                            isConnecting = false,
                            connectionStatusText = "Connected to ${state.serverUrl}",
                            successMessage = "Successfully connected!",
                            password = "", // Clear password after successful login
                        )
                    }

                    // Save settings
                    settingsManager.updateSettings {
                        it.copy(
                            serverUrl = state.serverUrl,
                            username = state.username,
                        )
                    }

                    // Check server reachability
                    connectivityMonitor.checkServerReachable()
                } else {
                    _uiState.update {
                        it.copy(
                            isConnected = false,
                            isConnecting = false,
                            connectionStatusText = "Connection failed",
                            errorMessage = apiService.lastError
                                ?: "Login failed. Check your credentials and server URL.",
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isConnected = false,
                        isConnecting = false,
                        connectionStatusText = "Connection failed",
                        errorMessage = "Connection error: ${e.message}",
                    )
                }
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            try {
                apiService.logout()
                _uiState.update {
                    it.copy(
                        isConnected = false,
                        connectionStatusText = "Not connected",
                        successMessage = "Disconnected successfully",
                        password = "",
                        errorMessage = null,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(errorMessage = "Error disconnecting: ${e.message}")
                }
            }
        }
    }

    fun testConnection() {
        if (!_uiState.value.isConnected) {
            _uiState.update { it.copy(errorMessage = "Not connected. Please connect first.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null, successMessage = null) }

            try {
                val valid = apiService.validateToken()
                if (valid) {
                    _uiState.update { it.copy(successMessage = "Connection test successful!") }
                } else {
                    _uiState.update {
                        it.copy(
                            errorMessage = "Connection test failed. Token may be expired.",
                            isConnected = false,
                            connectionStatusText = "Disconnected (token expired)",
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(errorMessage = "Connection test error: ${e.message}")
                }
            }
        }
    }

    fun syncNow() {
        if (!_uiState.value.isConnected) {
            _uiState.update { it.copy(errorMessage = "Not connected. Please connect first.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null, successMessage = null) }
            try {
                syncManager.syncNow()
                _uiState.update { it.copy(successMessage = "Sync completed successfully") }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Sync failed: ${e.message}") }
            }
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null, successMessage = null) }

            try {
                // Only clear library/audiobook cache — NOT progress, downloads, or pending syncs.
                // clearAllTables() would wipe playback positions, download records, and the
                // offline queue, causing silent data loss.
                audioBookDao.deleteAll()
                libraryDao.deleteAll()
                _uiState.update { it.copy(successMessage = "Cache cleared successfully") }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to clear cache: ${e.message}") }
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun dismissSuccess() {
        _uiState.update { it.copy(successMessage = null) }
    }

    fun toggleAnomalies() {
        viewModelScope.launch {
            unhingedRepository.updateSettings { it.copy(anomaliesEnabled = !it.anomaliesEnabled) }
        }
    }

    fun toggleWhispers() {
        viewModelScope.launch {
            unhingedRepository.updateSettings { it.copy(whispersEnabled = !it.whispersEnabled) }
        }
    }

    fun toggleReduceMotion() {
        viewModelScope.launch {
            unhingedRepository.updateSettings { it.copy(reduceMotionRequested = !it.reduceMotionRequested) }
        }
    }

    // ─── Equalizer ───────────────────────────────────────────────────────

    fun toggleEq() {
        val newEnabled = !_uiState.value.eqEnabled
        playbackManager.setEqEnabled(newEnabled)
        viewModelScope.launch {
            settingsManager.updateSettings { it.copy(eqEnabled = newEnabled) }
        }
    }

    fun setEqBandGain(band: Int, gainMillibels: Int) {
        playbackManager.setEqBandGain(band, gainMillibels)
        viewModelScope.launch {
            settingsManager.updateSettings { it.copy(eqBandGains = playbackManager.eqBandGains.value) }
        }
    }

    fun resetEq() {
        val bandCount = _uiState.value.eqBandGains.size
        for (i in 0 until bandCount) {
            playbackManager.setEqBandGain(i, 0)
        }
        viewModelScope.launch {
            settingsManager.updateSettings { it.copy(eqBandGains = List(bandCount) { 0 }) }
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private fun getAppVersion(): String =
        "Į̷̙̑̚n̷̲̙͘f̷̰̎̄̂̚i̸̗͎̟̤͛̑̊͐͆̋͑͐̍̕n̶͍͂͊̃i̸̳͂́t̸̡̡̙̪̤̠̀͂͗͜ŷ̴̛̭̪͉̪͕̻̻͙̿̀̅̑͑͛̓͑͜"
}
