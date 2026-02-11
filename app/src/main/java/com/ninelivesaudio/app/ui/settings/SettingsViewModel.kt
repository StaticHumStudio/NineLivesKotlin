package com.ninelivesaudio.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninelivesaudio.app.data.local.AppDatabase
import com.ninelivesaudio.app.data.remote.ApiService
import com.ninelivesaudio.app.service.ConnectivityMonitor
import com.ninelivesaudio.app.service.ConnectivityMonitor.ConnectionStatus
import com.ninelivesaudio.app.service.SettingsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsManager: SettingsManager,
    private val apiService: ApiService,
    private val connectivityMonitor: ConnectivityMonitor,
    private val database: AppDatabase,
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

        // Messages
        val errorMessage: String? = null,
        val successMessage: String? = null,

        // Diagnostics
        val appVersion: String = "1.0.0",
        val settingsFilePath: String = "",
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
            val valid = apiService.validateToken()
            _uiState.update {
                it.copy(
                    isConnected = valid,
                    connectionStatusText = if (valid) "Connected to ${settings.serverUrl}" else "Not connected",
                )
            }
        }
    }

    // ─── User Actions ─────────────────────────────────────────────────────

    fun onServerUrlChanged(value: String) {
        _uiState.update { it.copy(serverUrl = value) }
    }

    fun onUsernameChanged(value: String) {
        _uiState.update { it.copy(username = value) }
    }

    fun onPasswordChanged(value: String) {
        _uiState.update { it.copy(password = value) }
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

    fun clearCache() {
        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null, successMessage = null) }

            try {
                database.clearAllTables()
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

    // ─── Helpers ──────────────────────────────────────────────────────────

    private fun getAppVersion(): String {
        return "1.0.0" // Will be replaced with BuildConfig in Phase 10
    }
}
