package com.ninelivesaudio.app.ui.settings

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninelivesaudio.app.BuildConfig
import com.ninelivesaudio.app.data.local.dao.AudioBookDao
import com.ninelivesaudio.app.data.local.dao.LibraryDao
import com.ninelivesaudio.app.data.remote.ApiService
import com.ninelivesaudio.app.data.repository.LibraryRepository
import com.ninelivesaudio.app.domain.model.Library
import com.ninelivesaudio.app.service.ConnectivityMonitor
import com.ninelivesaudio.app.service.ConnectivityMonitor.ConnectionStatus
import com.ninelivesaudio.app.service.PlaybackManager
import com.ninelivesaudio.app.service.SettingsManager
import com.ninelivesaudio.app.service.SyncManager
import com.ninelivesaudio.app.settings.unhinged.UnhingedSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsManager: SettingsManager,
    private val apiService: ApiService,
    private val connectivityMonitor: ConnectivityMonitor,
    private val audioBookDao: AudioBookDao,
    private val libraryDao: LibraryDao,
    private val libraryRepository: LibraryRepository,
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
        val useApiToken: Boolean = false,
        val apiToken: String = "",
        val isConnected: Boolean = false,
        val isConnecting: Boolean = false,
        val connectionStatusText: String = "Not connected",
        val connectionStatus: ConnectionStatus = ConnectionStatus.OFFLINE,

        // Libraries
        val libraries: List<Library> = emptyList(),
        val selectedLibrary: Library? = null,

        // Security
        val allowSelfSignedCertificates: Boolean = false,
        val trustedFingerprintHost: String? = null,
        val hasTrustedFingerprint: Boolean = false,

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

        // Auto-Rewind
        val autoRewindEnabled: Boolean = true,
        val autoRewindMode: String = "smart",
        val autoRewindSeconds: Int = 15,

        // Sleep Timer
        val sleepTimerMotionEnabled: Boolean = true,
        val sleepTimerShakeResetEnabled: Boolean = true,
        val sleepTimerRewindSeconds: Int = 15,

        // Feedback Report
        val reportType: ReportType = ReportType.BUG,
        val includeLogsInReport: Boolean = true,
        val isCollectingReport: Boolean = false,
    )

    enum class ReportType(val label: String, val subjectPrefix: String) {
        BUG("Bug Report", "[NineLives Bug]"),
        UPGRADE("Upgrade Request", "[NineLives Request]"),
    }

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
            val configuredHost = extractHost(settings.serverUrl)
            state.copy(
                serverUrl = settings.serverUrl,
                username = settings.username,
                useApiToken = settings.useApiToken,
                allowSelfSignedCertificates = settings.allowSelfSignedCertificates,
                trustedFingerprintHost = configuredHost,
                hasTrustedFingerprint = configuredHost?.let {
                    settingsManager.getTrustedCertificateFingerprint(it) != null
                } == true,
                settingsFilePath = settingsManager.settingsFilePath,
                appVersion = getAppVersion(),
                autoRewindEnabled = settings.autoRewindEnabled,
                autoRewindMode = settings.autoRewindMode,
                autoRewindSeconds = settings.autoRewindSeconds,
                sleepTimerMotionEnabled = settings.sleepTimerMotionEnabled,
                sleepTimerShakeResetEnabled = settings.sleepTimerShakeResetEnabled,
                sleepTimerRewindSeconds = settings.sleepTimerRewindSeconds,
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
                if (valid) {
                    loadLibraries()
                } else {
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
        val serverUrl = value.trim()
        val host = extractHost(serverUrl)
        _uiState.update {
            it.copy(
                serverUrl = serverUrl,
                errorMessage = null,
                trustedFingerprintHost = host,
                hasTrustedFingerprint = host?.let { configuredHost ->
                    settingsManager.getTrustedCertificateFingerprint(configuredHost) != null
                } == true,
            )
        }
    }

    fun onUsernameChanged(value: String) {
        _uiState.update { it.copy(username = value.trim(), errorMessage = null) }
    }

    fun onPasswordChanged(value: String) {
        _uiState.update { it.copy(password = value, errorMessage = null) }
    }

    fun onUseApiTokenChanged(value: Boolean) {
        _uiState.update { it.copy(useApiToken = value, errorMessage = null) }
    }

    fun onApiTokenChanged(value: String) {
        _uiState.update { it.copy(apiToken = value, errorMessage = null) }
    }

    fun onAllowSelfSignedChanged(value: Boolean) {
        _uiState.update { it.copy(allowSelfSignedCertificates = value) }
        viewModelScope.launch {
            settingsManager.updateSettings { it.copy(allowSelfSignedCertificates = value) }
        }
    }

    fun resetTrustedCertificateFingerprint() {
        val host = _uiState.value.trustedFingerprintHost
        if (host.isNullOrBlank()) {
            _uiState.update { it.copy(errorMessage = "Set a valid server URL before resetting trust") }
            return
        }

        settingsManager.clearTrustedCertificateFingerprint(host)
        _uiState.update {
            it.copy(
                hasTrustedFingerprint = false,
                successMessage = "Trusted certificate fingerprint reset for $host",
                errorMessage = null,
            )
        }
    }

    // ─── Auto-Rewind Settings ─────────────────────────────────────────────

    fun setAutoRewindEnabled(enabled: Boolean) {
        _uiState.update { it.copy(autoRewindEnabled = enabled) }
        viewModelScope.launch {
            settingsManager.updateSettings { it.copy(autoRewindEnabled = enabled) }
        }
    }

    fun setAutoRewindMode(mode: String) {
        _uiState.update { it.copy(autoRewindMode = mode) }
        viewModelScope.launch {
            settingsManager.updateSettings { it.copy(autoRewindMode = mode) }
        }
    }

    fun setAutoRewindSeconds(seconds: Int) {
        _uiState.update { it.copy(autoRewindSeconds = seconds) }
        viewModelScope.launch {
            settingsManager.updateSettings { it.copy(autoRewindSeconds = seconds) }
        }
    }

    // ─── Sleep Timer Settings ─────────────────────────────────────────────

    fun setSleepTimerMotionEnabled(enabled: Boolean) {
        _uiState.update { it.copy(sleepTimerMotionEnabled = enabled) }
        viewModelScope.launch {
            settingsManager.updateSettings { it.copy(sleepTimerMotionEnabled = enabled) }
        }
    }

    fun setSleepTimerShakeResetEnabled(enabled: Boolean) {
        _uiState.update { it.copy(sleepTimerShakeResetEnabled = enabled) }
        viewModelScope.launch {
            settingsManager.updateSettings { it.copy(sleepTimerShakeResetEnabled = enabled) }
        }
    }

    fun setSleepTimerRewindSeconds(seconds: Int) {
        _uiState.update { it.copy(sleepTimerRewindSeconds = seconds) }
        viewModelScope.launch {
            settingsManager.updateSettings { it.copy(sleepTimerRewindSeconds = seconds) }
        }
    }

    fun connect() {
        val state = _uiState.value

        if (state.serverUrl.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Please enter a server URL") }
            return
        }
        if (state.useApiToken) {
            if (state.apiToken.isBlank()) {
                _uiState.update { it.copy(errorMessage = "Please enter an API token") }
                return
            }
        } else {
            if (state.username.isBlank() || state.password.isBlank()) {
                _uiState.update { it.copy(errorMessage = "Please enter username and password") }
                return
            }
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
                val success = if (state.useApiToken) {
                    apiService.loginWithToken(state.serverUrl, state.apiToken)
                } else {
                    apiService.login(state.serverUrl, state.username, state.password)
                }

                if (success) {
                    _uiState.update {
                        it.copy(
                            isConnected = true,
                            isConnecting = false,
                            connectionStatusText = "Connected to ${state.serverUrl}",
                            successMessage = "Successfully connected!",
                            password = "",
                            apiToken = "", // Clear token after successful login
                        )
                    }

                    // Save settings
                    settingsManager.updateSettings {
                        it.copy(
                            serverUrl = state.serverUrl,
                            username = if (state.useApiToken) "" else state.username,
                            useApiToken = state.useApiToken,
                        )
                    }

                    // Load libraries for the selector
                    loadLibraries()

                    // Check server reachability
                    connectivityMonitor.checkServerReachable()
                } else {
                    _uiState.update {
                        it.copy(
                            isConnected = false,
                            isConnecting = false,
                            connectionStatusText = "Connection failed",
                            errorMessage = apiService.lastError
                                ?: if (state.useApiToken) "Invalid API token."
                                   else "Login failed. Check your credentials and server URL.",
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

    fun refreshConnection() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(errorMessage = null, successMessage = null, isConnecting = true)
            }
            try {
                val hasToken = settingsManager.getAuthToken()?.isNotEmpty() == true
                if (!hasToken) {
                    _uiState.update {
                        it.copy(
                            isConnecting = false,
                            isConnected = false,
                            connectionStatusText = "No auth token — please reconnect",
                        )
                    }
                    return@launch
                }
                val valid = apiService.validateToken()
                _uiState.update {
                    it.copy(
                        isConnecting = false,
                        isConnected = valid,
                        connectionStatusText = if (valid) {
                            "Connected to ${settingsManager.currentSettings.serverUrl}"
                        } else {
                            "Session expired — please reconnect"
                        },
                        successMessage = if (valid) "Connection refreshed" else null,
                        errorMessage = if (!valid) "Token expired — please reconnect" else null,
                    )
                }
                if (valid) {
                    loadLibraries()
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isConnecting = false,
                        errorMessage = "Refresh failed: ${e.message}",
                    )
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

    // ─── Feedback Report ─────────────────────────────────────────────────

    fun onReportTypeChanged(type: ReportType) {
        _uiState.update { it.copy(reportType = type) }
    }

    fun onIncludeLogsChanged(include: Boolean) {
        _uiState.update { it.copy(includeLogsInReport = include) }
    }

    /**
     * Builds the full report body with device diagnostics and optionally logcat,
     * then invokes [onReady] with (subject, body) on the main thread so the
     * composable can launch the email intent.
     */
    fun buildReport(onReady: (subject: String, body: String) -> Unit) {
        val state = _uiState.value
        _uiState.update { it.copy(isCollectingReport = true) }

        viewModelScope.launch {
            val subject = "${state.reportType.subjectPrefix} ${getAppVersion()}"
            val diagnostics = buildDiagnostics(state)
            val logs = if (state.includeLogsInReport) collectLogcat() else null

            val body = buildString {
                appendLine("--- ${state.reportType.label} ---")
                appendLine()
                appendLine("[Describe the issue or request here]")
                appendLine()
                appendLine()
                appendLine("─── Device & App Info ───")
                append(diagnostics)
                if (logs != null) {
                    appendLine()
                    appendLine()
                    appendLine("─── Recent Logs (last 500 lines) ───")
                    appendLine(logs)
                }
            }

            _uiState.update { it.copy(isCollectingReport = false) }
            onReady(subject, body)
        }
    }

    private fun buildDiagnostics(state: UiState): String = buildString {
        appendLine("App Version: ${getAppVersion()}")
        appendLine("Build Type: ${BuildConfig.BUILD_TYPE}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Connection: ${state.connectionStatusText}")
        appendLine("EQ Enabled: ${state.eqEnabled}")
        appendLine("Auto-Rewind: ${if (state.autoRewindEnabled) "${state.autoRewindMode} (${state.autoRewindSeconds}s)" else "Off"}")
        appendLine("Sleep Motion: ${state.sleepTimerMotionEnabled}, Shake: ${state.sleepTimerShakeResetEnabled}")
    }

    private suspend fun collectLogcat(): String = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-t", "500", "--pid=${android.os.Process.myPid()}")
            )
            process.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "(Failed to collect logs: ${e.message})"
        }
    }

    private fun extractHost(serverUrl: String): String? {
        if (serverUrl.isBlank()) return null
        return try {
            URI(serverUrl).host?.lowercase()
        } catch (_: Exception) {
            null
        }
    }

    // ─── Library Selection ──────────────────────────────────────────────

    private suspend fun loadLibraries() {
        try {
            // Load local first
            var libs = libraryRepository.getAll()

            // Sync from server if possible
            try {
                val serverLibs = libraryRepository.syncFromServer()
                if (serverLibs.isNotEmpty()) libs = serverLibs
            } catch (_: Exception) {
                // Use cached
            }

            // Restore persisted selection, fall back to first available
            val savedId = settingsManager.currentSettings.selectedLibraryId
            val selected = libs.firstOrNull { it.id == savedId } ?: libs.firstOrNull()

            // Keep persisted selection in sync with the effective default.
            // Without this, UI can show a selected library while the rest of
            // the app still behaves as "all libraries" (null selection).
            if (selected != null && selected.id != savedId) {
                settingsManager.updateSettings { it.copy(selectedLibraryId = selected.id) }
            }

            _uiState.update {
                it.copy(
                    libraries = libs,
                    selectedLibrary = selected,
                )
            }
        } catch (_: Exception) {
            // Non-critical — library selector just won't appear
        }
    }

    fun onLibrarySelected(library: Library) {
        _uiState.update { it.copy(selectedLibrary = library) }
        viewModelScope.launch {
            // Persist selection so the whole app picks it up
            settingsManager.updateSettings { it.copy(selectedLibraryId = library.id) }
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private fun getAppVersion(): String {
        val versionName = com.ninelivesaudio.app.BuildConfig.VERSION_NAME
        val versionCode = com.ninelivesaudio.app.BuildConfig.VERSION_CODE
        return "v$versionName ($versionCode)"
    }
}
