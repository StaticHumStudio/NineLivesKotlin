package com.ninelivesaudio.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninelivesaudio.app.BuildConfig
import com.ninelivesaudio.app.data.local.dao.AudioBookDao
import com.ninelivesaudio.app.data.local.dao.LibraryDao
import com.ninelivesaudio.app.data.remote.ApiService
import com.ninelivesaudio.app.data.remote.CredentialLoginResult
import com.ninelivesaudio.app.data.remote.StoredTokenValidation
import com.ninelivesaudio.app.data.remote.TokenValidationResult
import com.ninelivesaudio.app.data.repository.AudioBookRepository
import com.ninelivesaudio.app.data.repository.LibraryRepository
import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.domain.model.Library
import com.ninelivesaudio.app.domain.model.ThemeMode
import com.ninelivesaudio.app.service.ConnectivityMonitor
import com.ninelivesaudio.app.service.ConnectivityMonitor.ConnectionStatus
import com.ninelivesaudio.app.service.PlaybackManager
import com.ninelivesaudio.app.service.SettingsManager
import com.ninelivesaudio.app.service.SyncManager
import com.ninelivesaudio.app.service.local.LocalLibraryScanner
import com.ninelivesaudio.app.service.local.LocalFolderAccess
import com.ninelivesaudio.app.service.local.LocalMetadataExtractor
import com.ninelivesaudio.app.service.local.toAudioBook
import com.ninelivesaudio.app.settings.unhinged.UnhingedSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsManager: SettingsManager,
    private val apiService: ApiService,
    private val connectivityMonitor: ConnectivityMonitor,
    private val audioBookDao: AudioBookDao,
    private val libraryDao: LibraryDao,
    private val libraryRepository: LibraryRepository,
    private val audioBookRepository: AudioBookRepository,
    private val unhingedRepository: UnhingedSettingsRepository,
    private val syncManager: SyncManager,
    private val playbackManager: PlaybackManager,
    private val localScanner: LocalLibraryScanner,
    private val localMetadataExtractor: LocalMetadataExtractor,
    private val localFolderAccess: LocalFolderAccess,
) : ViewModel() {

    // ─── UI State ─────────────────────────────────────────────────────────

    data class UiState(
        // Mode
        val appMode: AppMode = AppMode.LOCAL,

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

        // Libraries (ABS)
        val libraries: List<Library> = emptyList(),
        val selectedLibrary: Library? = null,

        // Local Libraries
        val localLibraries: List<Library> = emptyList(),
        val selectedLocalLibrary: Library? = null,
        val inaccessibleLocalLibraryIds: Set<String> = emptySet(),
        val isScanning: Boolean = false,
        val lastScanMessage: String? = null,

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

        // Appearance
        val themeMode: ThemeMode = ThemeMode.NOIR,

        // Equalizer
        val eqEnabled: Boolean = false,
        val eqBandGains: List<Int> = List(5) { 0 },
        val eqBandFrequencies: List<Int> = listOf(60, 230, 910, 3600, 14000),
        val eqBandRange: Pair<Int, Int> = Pair(-1500, 1500),

        // Auto-Rewind
        val autoRewindEnabled: Boolean = true,
        val autoRewindMode: String = "smart",
        val autoRewindSeconds: Int = 15,

        // Sleep Timer
        val skipSilenceEnabled: Boolean = false,
        val sleepTimerMotionEnabled: Boolean = true,
        val sleepTimerShakeResetEnabled: Boolean = true,
        val sleepTimerRewindSeconds: Int = 15,
        val includeArchivedInStats: Boolean = true,

        // Feedback Report
        val reportType: ReportType = ReportType.BUG,
        val includeLogsInReport: Boolean = false,
        val isCollectingReport: Boolean = false,

        // Archive sweep (permanent-delete confirm)
        val pendingSweep: PendingSweep? = null,
    )

    enum class ReportType(val label: String, val subjectPrefix: String) {
        BUG("Bug Report", "[NineLives Bug]"),
        FEATURE("Feature Request", "[NineLives Request]"),
    }

    /**
     * Archive-sweep scopes, narrowest to broadest:
     * - ORPHANED: archived books in libraries the app can no longer access
     *   (folder removed) — unrestorable, delete-only. Spans all libraries.
     * - DELETED: every archived book in the selected library.
     * - ALL: every book in the selected library, then remove the library itself.
     */
    enum class SweepType { ORPHANED, DELETED, ALL }

    /** A staged sweep awaiting confirmation, with the count it will delete. */
    data class PendingSweep(val type: SweepType, val count: Int)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    private val authUiGeneration = AtomicLong()
    private val authUiOperationMutex = Mutex()

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

        // Observe local libraries
        viewModelScope.launch {
            libraryRepository.observeLocalLibraries().collect { locals ->
                val savedLocalId = settingsManager.currentSettings.selectedLocalLibraryId
                val selected = resolveSelectedLocalLibrary(locals, savedLocalId)
                _uiState.update {
                    it.copy(
                        localLibraries = locals,
                        selectedLocalLibrary = selected,
                        inaccessibleLocalLibraryIds = locals
                            .map { library -> library.id }
                            .toSet() - localFolderAccess.accessibleLibraryIds(locals),
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
                appMode = settings.appMode,
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
                selectedLocalLibrary = resolveSelectedLocalLibrary(
                    state.localLibraries,
                    settings.selectedLocalLibraryId,
                ),
                autoRewindEnabled = settings.autoRewindEnabled,
                autoRewindMode = settings.autoRewindMode,
                autoRewindSeconds = settings.autoRewindSeconds,
                skipSilenceEnabled = settings.skipSilenceEnabled,
                sleepTimerMotionEnabled = settings.sleepTimerMotionEnabled,
                sleepTimerShakeResetEnabled = settings.sleepTimerShakeResetEnabled,
                sleepTimerRewindSeconds = settings.sleepTimerRewindSeconds,
                includeArchivedInStats = settings.includeArchivedInStats,
                themeMode = settings.themeMode,
            )
        }

        // Check if already connected by validating stored token. Only an
        // explicit INVALID verdict (server rejected the token) clears it — a
        // transient UNREACHABLE must keep the token so the user stays signed in
        // and reconnects automatically once the server is back.
        val uiGeneration = authUiGeneration.get()
        authUiOperationMutex.withLock {
            if (authUiGeneration.get() != uiGeneration) return@withLock
            try {
                val validation = apiService.validateStoredTokenSession() ?: return@withLock
                if (!validationResultIsCurrent(uiGeneration, validation)) return@withLock
                val result = validation.result
                when (result) {
                    TokenValidationResult.VALID -> {
                        _uiState.update { state ->
                            if (authUiGeneration.get() != uiGeneration) state else {
                                state.copy(
                                    isConnected = true,
                                    connectionStatusText = "Connected to ${validation.session.serverUrl}",
                                )
                            }
                        }
                    }
                    TokenValidationResult.INVALID -> {
                        if (apiService.logoutIfCurrentSession(validation.session)) {
                            _uiState.update { state ->
                                if (authUiGeneration.get() != uiGeneration) state else {
                                    state.copy(
                                        isConnected = false,
                                        connectionStatusText = "Session expired — please reconnect",
                                    )
                                }
                            }
                        }
                    }
                    TokenValidationResult.UNREACHABLE -> {
                        // Keep the token; just reflect that we couldn't reach the server.
                        _uiState.update { state ->
                            if (authUiGeneration.get() != uiGeneration) state else {
                                state.copy(
                                    isConnected = false,
                                    connectionStatusText = "Server unreachable — will retry automatically",
                                )
                            }
                        }
                    }
                }

                // Keep validation and its library side effect in one serialized
                // auth UI operation. A newer Connect or Disconnect cannot enter
                // between the current-session check and loadLibraries().
                if (
                    shouldLoadCachedLibrariesAfterValidation(result) &&
                    validationResultIsCurrent(uiGeneration, validation)
                ) {
                    loadLibraries()
                }
            } catch (e: Exception) {
                _uiState.update { state ->
                    if (authUiGeneration.get() != uiGeneration) state else {
                        state.copy(
                            isConnected = false,
                            connectionStatusText = "Connection check failed: ${e.message}",
                        )
                    }
                }
            }
        }
    }

    private suspend fun validationResultIsCurrent(
        uiGeneration: Long,
        validation: StoredTokenValidation,
    ): Boolean = shouldApplyStoredValidation(
        result = validation.result,
        uiGenerationUnchanged = authUiGeneration.get() == uiGeneration,
        authSessionCurrent = apiService.isCurrentAuthSession(validation.session),
    )

    private fun updateAuthUi(uiGeneration: Long, transform: (UiState) -> UiState) {
        _uiState.update { state ->
            if (authUiGeneration.get() == uiGeneration) transform(state) else state
        }
    }

    private suspend fun validateRetainedSession(): TokenValidationResult {
        val validation = apiService.validateStoredTokenSession(forceRefresh = true)
            ?: return TokenValidationResult.UNREACHABLE
        if (validation.result == TokenValidationResult.INVALID) {
            apiService.logoutIfCurrentSession(validation.session)
        }
        return validation.result
    }

    // ─── Mode Switching ───────────────────────────────────────────────────

    fun switchMode(mode: AppMode) {
        if (mode == _uiState.value.appMode) return
        // Stop playback to avoid cross-mode player state
        playbackManager.stop()
        _uiState.update { it.copy(appMode = mode) }
        viewModelScope.launch {
            settingsManager.updateSettings { it.copy(appMode = mode) }
        }
    }

    // ─── Local Library Actions ────────────────────────────────────────────

    /**
     * Called by SettingsScreen after the SAF folder picker returns a URI.
     * The composable must call contentResolver.takePersistableUriPermission
     * before passing the URI string here.
     */
    fun onLocalFolderPicked(uriString: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true, lastScanMessage = null, errorMessage = null) }
            try {
                // Derive a display name from the URI path
                val uri = Uri.parse(uriString)
                val displayName = uri.lastPathSegment
                    ?.substringAfterLast(':')
                    ?.substringAfterLast('/')
                    ?.ifBlank { null }
                    ?: "Local Library"

                val scanResult = withContext(Dispatchers.IO) {
                    localScanner.scan(uri)
                }
                if (scanResult.errorMessages.isNotEmpty() && scanResult.books.isEmpty()) {
                    throw IllegalStateException(scanResult.errorMessages.joinToString("; "))
                }

                // Create or reuse the local library row after confirming the folder is readable.
                val library = libraryRepository.createLocalLibrary(displayName, uriString)

                // Import discovered books, but only delete missing books after a clean scan.
                val books = scanResult.books.map { it.toAudioBook(library.id) }
                audioBookRepository.importLocalBooks(library.id, books)
                removeMissingBooksAfterSuccessfulScan(library.id, scanResult)

                // Select this library
                settingsManager.updateSettings {
                    it.copy(selectedLocalLibraryId = library.id)
                }

                val msg = "${scanResult.books.size} books imported" +
                    if (scanResult.skippedCount > 0) ", ${scanResult.skippedCount} skipped" else ""

                // A clean scan (no errors) that still found nothing needs an explanation,
                // not a cheerful zero. Everything else about the import/archive flow runs
                // the same either way.
                val emptyScanMessage = if (scanResult.books.isEmpty() && scanResult.errorMessages.isEmpty()) {
                    "No books found in ${scanResult.foldersScanned} folders. Nine Lives looks " +
                        "for folders with audio files inside. Check the folder layout guide in Settings."
                } else {
                    null
                }

                _uiState.update {
                    it.copy(
                        isScanning = false,
                        lastScanMessage = msg,
                        selectedLocalLibrary = library,
                        inaccessibleLocalLibraryIds = it.inaccessibleLocalLibraryIds - library.id,
                        successMessage = if (emptyScanMessage != null) null else msg,
                        errorMessage = emptyScanMessage,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isScanning = false,
                        errorMessage = "Scan failed: ${e.message}",
                    )
                }
            }
        }
    }

    fun rescanLocalLibrary(library: Library) {
        val folderUri = library.folderUri ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true, lastScanMessage = null, errorMessage = null) }
            try {
                val scanResult = withContext(Dispatchers.IO) {
                    localScanner.scan(Uri.parse(folderUri))
                }
                if (scanResult.errorMessages.isNotEmpty() && scanResult.books.isEmpty()) {
                    throw IllegalStateException(scanResult.errorMessages.joinToString("; "))
                }

                // Import discovered books, but only delete missing books after a clean scan.
                val books = scanResult.books.map { it.toAudioBook(library.id) }
                audioBookRepository.importLocalBooks(library.id, books)
                removeMissingBooksAfterSuccessfulScan(library.id, scanResult)

                val msg = "${scanResult.books.size} books found" +
                    if (scanResult.skippedCount > 0) ", ${scanResult.skippedCount} skipped" else ""

                // A clean scan (no errors) that still found nothing needs an explanation,
                // not a cheerful zero. Everything else about the import/archive flow runs
                // the same either way.
                val emptyScanMessage = if (scanResult.books.isEmpty() && scanResult.errorMessages.isEmpty()) {
                    "No books found in ${scanResult.foldersScanned} folders. Nine Lives looks " +
                        "for folders with audio files inside. Check the folder layout guide in Settings."
                } else {
                    null
                }

                _uiState.update {
                    it.copy(
                        isScanning = false,
                        lastScanMessage = msg,
                        successMessage = if (emptyScanMessage != null) null else "Rescan complete: $msg",
                        errorMessage = emptyScanMessage,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isScanning = false,
                        errorMessage = "Rescan failed: ${e.message}",
                    )
                }
            }
        }
    }

    fun removeLocalLibrary(library: Library) {
        viewModelScope.launch {
            try {
                // Soft-delete: archive every book in the folder (empty scan =>
                // all ids missing) and KEEP the library row. The Archive tab is
                // scoped by LibraryId, so deleting the row would orphan the
                // archived books — reachable in the Dossier but with no Archive
                // tab to browse. The row stays as an archive-only container;
                // re-adding the same folder re-imports and clears ArchivedAt
                // (restore). "Delete forever" / the archive sweep is the path
                // to actually remove them.
                archiveMissingBooks(library.id, scannedIds = emptyList())
                releaseSafPermission(library.folderUri)
                moveSelectionOffLibrary(library)

                _uiState.update {
                    it.copy(successMessage = "Archived '${library.name}'")
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(errorMessage = "Failed to remove library: ${e.message}")
                }
            }
        }
    }

    /**
     * If [library] is the selected local library, move the selection to another
     * local library (or clear it), so the app is never left pointing at a
     * library that just lost all its live books.
     */
    private suspend fun moveSelectionOffLibrary(library: Library) {
        if (_uiState.value.selectedLocalLibrary?.id != library.id) return
        val fallbackLocal = _uiState.value.localLibraries.firstOrNull { it.id != library.id }
        settingsManager.updateSettings {
            it.copy(selectedLocalLibraryId = fallbackLocal?.id)
        }
        _uiState.update { it.copy(selectedLocalLibrary = null) }
    }

    // ─── Archive sweep (permanently remove archived local data) ────────────

    /** Folder URIs the app still holds a persisted read grant for. */
    private fun accessibleFolderUris(): Set<String> =
        context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri.toString() }
            .toSet()

    /** Book ids a sweep of [type] would delete, resolved against current state. */
    private suspend fun sweepTargetIds(type: SweepType): List<String> = when (type) {
        SweepType.ORPHANED ->
            orphanedLibraries(_uiState.value.localLibraries, accessibleFolderUris())
                .flatMap { audioBookRepository.getArchivedLocalIds(it.id) }

        SweepType.DELETED ->
            _uiState.value.selectedLocalLibrary
                ?.let { audioBookRepository.getArchivedLocalIds(it.id) }
                ?: emptyList()

        SweepType.ALL ->
            _uiState.value.selectedLocalLibrary
                ?.let { audioBookRepository.getLocalIds(it.id) }
                ?: emptyList()
    }

    /** Stage a sweep: compute how many books it will delete, then confirm. */
    fun requestSweep(type: SweepType) {
        viewModelScope.launch {
            val count = sweepTargetIds(type).size
            if (count == 0) {
                _uiState.update { it.copy(successMessage = "Nothing to remove") }
            } else {
                _uiState.update { it.copy(pendingSweep = PendingSweep(type, count)) }
            }
        }
    }

    fun cancelSweep() {
        _uiState.update { it.copy(pendingSweep = null) }
    }

    /** Execute the staged sweep permanently (cascades rows, history, covers). */
    fun confirmSweep() {
        val pending = _uiState.value.pendingSweep ?: return
        _uiState.update { it.copy(pendingSweep = null) }
        viewModelScope.launch {
            try {
                val ids = sweepTargetIds(pending.type)
                // Stop playback if the live book is in the delete set, so the
                // session-sync coroutine can't re-create rows we remove.
                if (playbackManager.currentBook.value?.id in ids) {
                    playbackManager.stop()
                }
                audioBookRepository.deleteLocalBooksForever(ids)

                // "All Books" wipes the whole library — drop the now-empty row
                // and release its folder permission, then move selection off it.
                if (pending.type == SweepType.ALL) {
                    _uiState.value.selectedLocalLibrary?.let { library ->
                        libraryRepository.removeLocalLibrary(library.id)
                        releaseSafPermission(library.folderUri)
                        moveSelectionOffLibrary(library)
                    }
                }

                _uiState.update {
                    it.copy(successMessage = sweepMessage(pending.type, ids.size))
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Cleanup failed: ${e.message}") }
            }
        }
    }

    private fun sweepMessage(type: SweepType, count: Int): String {
        val noun = if (count == 1) "book" else "books"
        return when (type) {
            SweepType.ORPHANED -> "Removed $count orphaned $noun"
            SweepType.DELETED -> "Removed $count archived $noun"
            SweepType.ALL -> "Removed all data for this folder"
        }
    }

    private fun releaseSafPermission(folderUri: String?) {
        if (folderUri.isNullOrBlank()) return
        val uri = Uri.parse(folderUri)
        val persisted = context.contentResolver.persistedUriPermissions
            .firstOrNull { it.uri == uri }
            ?: return
        val flags = (if (persisted.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
            (if (persisted.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        if (flags == 0) return

        try {
            context.contentResolver.releasePersistableUriPermission(uri, flags)
        } catch (e: SecurityException) {
            // Permission was already released or never held — safe to ignore.
            Log.d("SettingsViewModel", "releasePersistableUriPermission: $e")
        }
    }

    fun onLocalFolderPermissionFailed(message: String) {
        _uiState.update {
            it.copy(
                isScanning = false,
                errorMessage = "Folder permission failed: $message",
            )
        }
    }

    fun onLocalLibrarySelected(library: Library) {
        _uiState.update { it.copy(selectedLocalLibrary = library) }
        viewModelScope.launch {
            settingsManager.updateSettings {
                it.copy(selectedLocalLibraryId = library.id)
            }
        }
    }

    private fun resolveSelectedLocalLibrary(
        localLibraries: List<Library>,
        savedLocalId: String?,
    ): Library? {
        return localLibraries.firstOrNull { it.id == savedLocalId } ?: localLibraries.firstOrNull()
    }

    private suspend fun removeMissingBooksAfterSuccessfulScan(
        libraryId: String,
        scanResult: LocalLibraryScanner.ScanResult,
    ) {
        if (scanResult.errorMessages.isEmpty()) {
            archiveMissingBooks(libraryId, scanResult.books.map { it.id })
        }
    }

    /**
     * The single archive choke point: before soft-deleting the books missing
     * from [scannedIds], copy any still-readable content:// folder cover to
     * durable storage. Both archive triggers (whole-folder Remove and the
     * missing-books pass after a rescan) route through here, so a book scanned
     * under an older build keeps its cover once archived — the folder is still
     * accessible at this point (permission not yet released; a rescan only
     * archives what a clean scan reported missing). Already-durable and
     * unreadable covers are left as-is (best-effort).
     */
    private suspend fun archiveMissingBooks(libraryId: String, scannedIds: List<String>) {
        audioBookRepository.persistFolderCovers(libraryId) { uri, id ->
            localMetadataExtractor.persistFolderCover(uri, id)
        }
        audioBookRepository.removeMissingLocalBooks(libraryId, scannedIds)
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

    // ─── Appearance Settings ──────────────────────────────────────────────

    fun setThemeMode(mode: ThemeMode) {
        _uiState.update { it.copy(themeMode = mode) }
        viewModelScope.launch {
            settingsManager.updateSettings { it.copy(themeMode = mode) }
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

    /**
     * Stores the user's choice. EffectiveSettings decides whether the engine
     * acts on it, so a free user can toggle this and keep the setting for when
     * they unlock rather than being told no.
     */
    fun setSkipSilenceEnabled(enabled: Boolean) {
        _uiState.update { it.copy(skipSilenceEnabled = enabled) }
        viewModelScope.launch {
            settingsManager.updateSettings { it.copy(skipSilenceEnabled = enabled) }
            playbackManager.applySkipSilence()
        }
    }

    fun setSleepTimerMotionEnabled(enabled: Boolean) {
        _uiState.update { it.copy(sleepTimerMotionEnabled = enabled) }
        viewModelScope.launch {
            settingsManager.updateSettings { it.copy(sleepTimerMotionEnabled = enabled) }
        }
    }

    fun toggleIncludeArchivedInStats() {
        val enabled = !_uiState.value.includeArchivedInStats
        _uiState.update { it.copy(includeArchivedInStats = enabled) }
        viewModelScope.launch {
            settingsManager.updateSettings { it.copy(includeArchivedInStats = enabled) }
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

        // Invalidates every startup validation side effect before the new login
        // coroutine can yield, including same-token reconnects.
        val uiGeneration = authUiGeneration.incrementAndGet()

        viewModelScope.launch {
            // Login, the settings activation, and loadLibraries stay inside the
            // lock so Connect/Disconnect/Refresh stay mutually exclusive for
            // those steps. syncNow() (a full library sync) and
            // checkServerReachable() run AFTER the lock is released — holding
            // the mutex across them left Disconnect dead for minutes. Both are
            // still generation-guarded, so a superseded Connect never runs them.
            var runPostLoginSync = false

            authUiOperationMutex.withLock {
                if (authUiGeneration.get() != uiGeneration) return@withLock
            updateAuthUi(uiGeneration) {
                it.copy(
                    isConnecting = true,
                    errorMessage = null,
                    successMessage = null,
                    connectionStatusText = "Connecting...",
                )
            }

            // Re-read the latest field values inside the coroutine. The snapshot
            // captured before launch can be stale if the user edited the URL,
            // username, or token between tapping Connect and this point, which
            // would otherwise log in to (and persist) the wrong server.
            val s = _uiState.value

            try {
                val passwordOutcome = if (s.useApiToken) {
                    null
                } else {
                    val hadStoredToken = !settingsManager.getAuthToken().isNullOrBlank()
                    // Snapshot the stored username BEFORE the login attempt —
                    // ApiService.login writes the attempted username into
                    // settings up front, so reading it afterward would always
                    // match trivially.
                    val storedUsername = settingsManager.currentSettings.username
                    resolvePasswordLogin(
                        credentialLogin = {
                            apiService.login(s.serverUrl, s.username, s.password)
                        },
                        hadStoredToken = hadStoredToken,
                        usernameMatchesStored = s.username.trim() == storedUsername.trim(),
                        validateRetainedToken = {
                            validateRetainedSession()
                        },
                    )
                }
                val success = passwordOutcome == PasswordLoginOutcome.NEW_SESSION ||
                    passwordOutcome == PasswordLoginOutcome.RETAINED_SESSION ||
                    (s.useApiToken && apiService.loginWithToken(s.serverUrl, s.apiToken))

                if (authUiGeneration.get() != uiGeneration) return@withLock
                if (success) {
                    // RETAINED_SESSION means the typed server never answered and
                    // the session that validated belongs to the STORED server
                    // (login() already rolled settings back to it). Persisting or
                    // displaying the typed URL would pair that server's token
                    // with a different host, and the next validation against the
                    // wrong host could 401 and clear a still-valid credential.
                    val retained = passwordOutcome == PasswordLoginOutcome.RETAINED_SESSION
                    val (sessionServerUrl, sessionUsername) = sessionIdentityForOutcome(
                        retained = retained,
                        typedServerUrl = s.serverUrl,
                        typedUsername = s.username,
                        storedServerUrl = settingsManager.currentSettings.serverUrl,
                        storedUsername = settingsManager.currentSettings.username,
                    )
                    updateAuthUi(uiGeneration) {
                        it.copy(
                            appMode = AppMode.AUDIOBOOKSHELF,
                            isConnected = true,
                            isConnecting = false,
                            serverUrl = sessionServerUrl,
                            username = sessionUsername,
                            connectionStatusText = "Connected to $sessionServerUrl",
                            successMessage = if (retained) {
                                "Connected with saved session"
                            } else {
                                "Successfully connected!"
                            },
                            password = "",
                            apiToken = "", // Clear token after successful login
                        )
                    }

                    activateSessionAfterLogin(
                        // Persist server mode before choosing a library. Otherwise
                        // loadLibraries deliberately refuses to replace the active
                        // local-folder selection and fresh login stays on Local.
                        activateAudiobookshelf = {
                            if (authUiGeneration.get() == uiGeneration) {
                                settingsManager.updateSettings {
                                    it.copy(
                                        appMode = AppMode.AUDIOBOOKSHELF,
                                        serverUrl = sessionServerUrl,
                                        username = if (s.useApiToken) "" else sessionUsername,
                                        useApiToken = if (retained) it.useApiToken else s.useApiToken,
                                    )
                                }
                            }
                        },
                        // Establish the active library before importing progress so
                        // Home observes the correct library as the database fills.
                        loadLibraries = {
                            if (authUiGeneration.get() == uiGeneration) loadLibraries()
                        },
                    )
                    runPostLoginSync = true
                } else {
                    updateAuthUi(uiGeneration) {
                        it.copy(
                            isConnected = false,
                            isConnecting = false,
                            connectionStatusText = if (passwordOutcome == PasswordLoginOutcome.UNREACHABLE) {
                                "Server unreachable"
                            } else {
                                "Connection failed"
                            },
                            errorMessage = when (passwordOutcome) {
                                PasswordLoginOutcome.UNREACHABLE ->
                                    "Could not reach the server. Your saved session was kept."
                                else -> apiService.lastError
                                    ?: if (s.useApiToken) "Invalid API token."
                                       else "Login failed. Check your credentials and server URL."
                            },
                        )
                    }
                }
            } catch (e: Exception) {
                updateAuthUi(uiGeneration) {
                    it.copy(
                        isConnected = false,
                        isConnecting = false,
                        connectionStatusText = "Connection failed",
                        errorMessage = "Connection error: ${e.message}",
                    )
                }
            }
            }

            if (runPostLoginSync) {
                try {
                    syncAfterLogin(
                        syncNow = {
                            if (authUiGeneration.get() == uiGeneration) syncManager.syncNow()
                        },
                        checkServerReachable = {
                            if (authUiGeneration.get() == uiGeneration) {
                                connectivityMonitor.checkServerReachable()
                            }
                        },
                    )
                } catch (e: Exception) {
                    // The connection itself already succeeded and the UI already
                    // reflects that — a failure here is a background sync hiccup,
                    // not a login failure, so it is logged rather than surfaced
                    // as a connection error.
                    Log.e("SettingsViewModel", "connect: Post-login sync failed", e)
                }
            }
        }
    }

    fun disconnect() {
        val uiGeneration = authUiGeneration.incrementAndGet()
        viewModelScope.launch {
            authUiOperationMutex.withLock {
                if (authUiGeneration.get() != uiGeneration) return@withLock
            try {
                apiService.logout()
                updateAuthUi(uiGeneration) {
                    it.copy(
                        isConnected = false,
                        connectionStatusText = "Not connected",
                        successMessage = "Disconnected successfully",
                        password = "",
                        errorMessage = null,
                    )
                }
            } catch (e: Exception) {
                updateAuthUi(uiGeneration) {
                    it.copy(errorMessage = "Error disconnecting: ${e.message}")
                }
            }
            }
        }
    }

    fun refreshConnection() {
        // Refresh observes the current auth intent. It must not supersede an
        // explicit queued Connect or Disconnect operation.
        val uiGeneration = authUiGeneration.get()
        viewModelScope.launch {
            authUiOperationMutex.withLock {
                if (authUiGeneration.get() != uiGeneration) return@withLock
            updateAuthUi(uiGeneration) {
                it.copy(errorMessage = null, successMessage = null, isConnecting = true)
            }
            try {
                val hasToken = settingsManager.getAuthToken()?.isNotEmpty() == true
                if (!hasToken) {
                    updateAuthUi(uiGeneration) {
                        it.copy(
                            isConnecting = false,
                            isConnected = false,
                            connectionStatusText = "No auth token — please reconnect",
                        )
                    }
                    return@withLock
                }
                // validateStoredTokenSession() returning null (no stored token,
                // or a verdict discarded because a concurrent auth mutation
                // moved the session) and a superseded uiGeneration both used to
                // bail out here with isConnecting left true forever — the
                // finally below is what actually resets it.
                val validation = apiService.validateStoredTokenSession() ?: return@withLock
                if (!validationResultIsCurrent(uiGeneration, validation)) return@withLock
                dispatchStoredValidation(
                    result = validation.result,
                    onValid = {
                        updateAuthUi(uiGeneration) {
                            it.copy(
                                isConnecting = false,
                                isConnected = true,
                                connectionStatusText = "Connected to ${validation.session.serverUrl}",
                                successMessage = "Connection refreshed",
                                errorMessage = null,
                            )
                        }
                        if (validationResultIsCurrent(uiGeneration, validation)) {
                            loadLibraries()
                        }
                    },
                    clearInvalidSession = {
                        apiService.logoutIfCurrentSession(validation.session)
                    },
                    onInvalidCleared = {
                        updateAuthUi(uiGeneration) {
                            it.copy(
                                isConnecting = false,
                                isConnected = false,
                                connectionStatusText = "Session expired — please reconnect",
                                successMessage = null,
                                errorMessage = "Token expired — please reconnect",
                            )
                        }
                    },
                    onUnreachable = {
                        updateAuthUi(uiGeneration) {
                            it.copy(
                                isConnecting = false,
                                isConnected = false,
                                connectionStatusText = "Server unreachable — will retry automatically",
                                successMessage = null,
                                errorMessage = "Could not reach the server. Your saved session was kept.",
                            )
                        }
                    },
                )
            } catch (e: Exception) {
                updateAuthUi(uiGeneration) {
                    it.copy(
                        isConnecting = false,
                        errorMessage = "Refresh failed: ${e.message}",
                    )
                }
            } finally {
                // Belt-and-suspenders for the two early-return paths above: no
                // matter how this block exits, isConnecting must not stay true.
                // A no-op for every path that already set it false explicitly.
                updateAuthUi(uiGeneration) { it.copy(isConnecting = false) }
            }
            }
        }
    }

    fun testConnection() {
        if (!_uiState.value.isConnected) {
            _uiState.update { it.copy(errorMessage = "Not connected. Please connect first.") }
            return
        }

        // A connection test is observational too. Only Connect and Disconnect
        // advance the generation that determines the winning auth intent.
        val uiGeneration = authUiGeneration.get()
        viewModelScope.launch {
            authUiOperationMutex.withLock {
                if (authUiGeneration.get() != uiGeneration) return@withLock
            updateAuthUi(uiGeneration) { it.copy(errorMessage = null, successMessage = null) }

            try {
                val validation = apiService.validateStoredTokenSession() ?: return@launch
                if (!validationResultIsCurrent(uiGeneration, validation)) return@launch
                dispatchStoredValidation(
                    result = validation.result,
                    onValid = {
                        updateAuthUi(uiGeneration) {
                            it.copy(successMessage = "Connection test successful!")
                        }
                    },
                    clearInvalidSession = {
                        apiService.logoutIfCurrentSession(validation.session)
                    },
                    onInvalidCleared = {
                        updateAuthUi(uiGeneration) {
                            it.copy(
                                errorMessage = "Connection test failed. Token expired.",
                                isConnected = false,
                                connectionStatusText = "Disconnected (token expired)",
                            )
                        }
                    },
                    onUnreachable = {
                        updateAuthUi(uiGeneration) {
                            it.copy(
                                errorMessage = "Connection test could not reach the server. Your saved session was kept.",
                                isConnected = false,
                                connectionStatusText = "Server unreachable — will retry automatically",
                            )
                        }
                    },
                )
            } catch (e: Exception) {
                updateAuthUi(uiGeneration) {
                    it.copy(errorMessage = "Connection test error: ${e.message}")
                }
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
                // Only clear ABS-source library/audiobook cache — NOT progress, downloads,
                // pending syncs, or Local Library configuration. clearAllTables() would wipe
                // playback positions, download records, the offline queue, and any folders
                // the user added in Local mode, causing silent data loss.
                audioBookDao.deleteAudiobookshelf()
                libraryDao.deleteAudiobookshelf()
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
            try {
                val subject = "${state.reportType.subjectPrefix} ${getAppVersion()}"
                val diagnostics = buildDiagnostics(state)
                val logs = if (state.includeLogsInReport) collectLogcat() else null

                val body = buildString {
                    appendLine("--- ${state.reportType.label} ---")
                    appendLine()
                    appendLine("[Describe the issue or request here]")
                    appendLine()
                    appendLine()
                    appendLine("Device and App Info")
                    append(diagnostics)
                    if (logs != null) {
                        appendLine()
                        appendLine()
                        appendLine("Recent Logs (last 500 lines)")
                        appendLine(logs)
                    }
                }

                onReady(subject, body)
            } finally {
                // Always clear the flag so the "collecting" spinner can never get
                // stuck if the build throws or the coroutine is cancelled.
                _uiState.update { it.copy(isCollectingReport = false) }
            }
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
            // This is the Audiobookshelf library selector, so load only ABS
            // libraries from cache — never Local folder roots. getAll() would
            // include local roots, and on the offline path (where the server
            // sync below returns nothing) they would leak into the ABS selector,
            // letting the user persist a local id as selectedLibraryId without
            // switching appMode and leaving ABS-scoped screens empty.
            var libs = libraryRepository.getAudiobookshelf()

            // Sync from server if possible (server returns ABS libraries only)
            try {
                val serverLibs = libraryRepository.syncFromServer()
                if (serverLibs.isNotEmpty()) libs = serverLibs
            } catch (_: Exception) {
                // Use cached
            }

            // Restore persisted selection, fall back to first available
            val appMode = settingsManager.currentSettings.appMode
            val savedId = settingsManager.currentSettings.selectedLibraryId
            val selected = libs.firstOrNull { it.id == savedId } ?: libs.firstOrNull()

            // Keep the persisted selection in sync with the effective default,
            // but ONLY in Audiobookshelf mode. In LOCAL mode selectedLibraryId
            // points at a local library the ABS selector can't see, so the
            // fallback above would be a server library — persisting it here
            // hijacks the app-wide selection and makes Home/Library show server
            // books in local mode (the "Nine Lives shows server books" bug).
            if (shouldPersistAbsSelection(appMode, selected?.id, savedId)) {
                settingsManager.updateSettings { it.copy(selectedLibraryId = selected!!.id) }
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

// ─── Init-path decisions (internal for testability) ───────────────────────

/**
 * Whether to populate the cached library selector after validating the stored
 * token. VALID and UNREACHABLE both keep the session, so the cache-backed
 * selector should appear (UNREACHABLE means offline / airplane mode, where the
 * selector is still useful and requires no network). INVALID means the token was
 * rejected and the user was logged out, so there is nothing to load.
 */
internal fun shouldLoadCachedLibrariesAfterValidation(result: TokenValidationResult): Boolean =
    result != TokenValidationResult.INVALID

internal fun shouldApplyStoredValidation(
    result: TokenValidationResult,
    uiGenerationUnchanged: Boolean,
    authSessionCurrent: Boolean,
): Boolean = when (result) {
    TokenValidationResult.VALID,
    TokenValidationResult.INVALID,
    TokenValidationResult.UNREACHABLE -> uiGenerationUnchanged && authSessionCurrent
}

internal suspend fun dispatchStoredValidation(
    result: TokenValidationResult,
    onValid: suspend () -> Unit,
    clearInvalidSession: suspend () -> Boolean,
    onInvalidCleared: suspend () -> Unit,
    onUnreachable: suspend () -> Unit,
) {
    when (result) {
        TokenValidationResult.VALID -> onValid()
        TokenValidationResult.INVALID -> {
            if (clearInvalidSession()) onInvalidCleared()
        }
        TokenValidationResult.UNREACHABLE -> onUnreachable()
    }
}

/**
 * Populate a fresh authenticated session immediately instead of waiting for
 * the periodic sync that may have already run before credentials existed.
 */
/**
 * The mutex-held half of a successful login: persisting the server mode
 * before choosing a library, then establishing the active library. Kept
 * inside authUiOperationMutex so Connect/Disconnect/Refresh stay mutually
 * exclusive for these two steps.
 */
internal suspend fun activateSessionAfterLogin(
    activateAudiobookshelf: suspend () -> Unit,
    loadLibraries: suspend () -> Unit,
) {
    activateAudiobookshelf()
    loadLibraries()
}

/**
 * The long-running tail of a successful login: a full library sync and a
 * reachability probe. Deliberately run AFTER authUiOperationMutex is
 * released — holding it across a full sync left Disconnect dead for the
 * sync's whole duration.
 */
internal suspend fun syncAfterLogin(
    syncNow: suspend () -> Unit,
    checkServerReachable: suspend () -> Unit,
) {
    syncNow()
    checkServerReachable()
}

/**
 * The server URL and username a successful connection may persist and display.
 * A retained session validated against the STORED server (login() rolled
 * settings back to it after the typed server never answered), so its identity
 * is the stored one. Pairing the retained token with the typed URL would let a
 * later validation against the wrong host 401 and clear a still-valid
 * credential. Only a genuinely new login owns the typed identity.
 */
internal fun sessionIdentityForOutcome(
    retained: Boolean,
    typedServerUrl: String,
    typedUsername: String,
    storedServerUrl: String,
    storedUsername: String,
): Pair<String, String> = if (retained) {
    storedServerUrl to storedUsername
} else {
    typedServerUrl to typedUsername
}

internal enum class PasswordLoginOutcome {
    NEW_SESSION,
    RETAINED_SESSION,
    FAILED,
    UNREACHABLE,
}

/**
 * A settings-load race in older builds could erase the server URL while the
 * encrypted auth token survived. Once the user restores that URL, prefer a
 * server-accepted retained token over a failed password attempt. This repairs
 * the session without discarding credentials that are actively playing media.
 *
 * Retained-session repair only ever runs for [CredentialLoginResult.UNREACHABLE]
 * — no verdict was reached, so the stored session might still be good. A
 * [CredentialLoginResult.REJECTED] attempt (wrong password, wrong user) is a
 * real answer from the server and must FAIL outright, even with a token
 * stored: falling back to the retained session there would silently report
 * success under the PREVIOUS user's session. The attempted username must also
 * match the stored one — an unreachable attempt against a different account
 * has no business repairing someone else's session.
 */
internal suspend fun resolvePasswordLogin(
    credentialLogin: suspend () -> CredentialLoginResult,
    hadStoredToken: Boolean,
    usernameMatchesStored: Boolean,
    validateRetainedToken: suspend () -> TokenValidationResult,
): PasswordLoginOutcome {
    return when (credentialLogin()) {
        CredentialLoginResult.SUCCESS -> PasswordLoginOutcome.NEW_SESSION
        CredentialLoginResult.REJECTED -> PasswordLoginOutcome.FAILED
        CredentialLoginResult.UNREACHABLE -> {
            if (!hadStoredToken || !usernameMatchesStored) return PasswordLoginOutcome.FAILED

            when (validateRetainedToken()) {
                TokenValidationResult.VALID -> PasswordLoginOutcome.RETAINED_SESSION
                TokenValidationResult.INVALID -> PasswordLoginOutcome.FAILED
                TokenValidationResult.UNREACHABLE -> PasswordLoginOutcome.UNREACHABLE
            }
        }
    }
}

/**
 * Whether the Audiobookshelf library selector may persist [selectedId] as the
 * app-wide `selectedLibraryId`. Only Audiobookshelf mode owns that value; in
 * LOCAL mode it belongs to the selected local library, and the ABS loader must
 * not overwrite it (doing so makes Home/Library show server books in local
 * mode). Also requires an actual change from [savedId].
 */
internal fun shouldPersistAbsSelection(
    appMode: AppMode,
    selectedId: String?,
    savedId: String?,
): Boolean = appMode == AppMode.AUDIOBOOKSHELF && selectedId != null && selectedId != savedId

// ─── Archive sweep decisions (internal for testability) ───────────────────

/**
 * The app can only manage a local library's books while it still holds a
 * persisted permission for the folder. Once the folder is removed the
 * permission is released, so the library's archived books are "orphaned":
 * they can no longer be rescanned/restored, only deleted.
 */
internal fun isLibraryFolderAccessible(
    folderUri: String?,
    accessibleFolderUris: Set<String>,
): Boolean = !folderUri.isNullOrBlank() && folderUri in accessibleFolderUris

/** Local libraries whose source folder the app can no longer access. */
internal fun orphanedLibraries(
    localLibraries: List<Library>,
    accessibleFolderUris: Set<String>,
): List<Library> =
    localLibraries.filter { !isLibraryFolderAccessible(it.folderUri, accessibleFolderUris) }
