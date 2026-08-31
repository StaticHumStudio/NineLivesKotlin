package com.ninelivesaudio.app.ui.home

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninelivesaudio.app.data.local.converter.effectiveCoverPath
import com.ninelivesaudio.app.data.local.dao.AudioBookDao
import com.ninelivesaudio.app.data.local.entity.RecentlyPlayedResult
import com.ninelivesaudio.app.data.repository.LibraryRepository
import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.service.ConnectivityMonitor
import com.ninelivesaudio.app.service.ConnectivityMonitor.ConnectionStatus
import com.ninelivesaudio.app.service.SettingsManager
import com.ninelivesaudio.app.service.SyncManager
import com.ninelivesaudio.app.service.local.LocalFolderAccess
import com.ninelivesaudio.app.ui.components.ConnectionStatusPresentation
import com.ninelivesaudio.app.ui.components.connectionStatusPresentation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val audioBookDao: AudioBookDao,
    private val connectivityMonitor: ConnectivityMonitor,
    private val syncManager: SyncManager,
    private val settingsManager: SettingsManager,
    private val libraryRepository: LibraryRepository,
    private val localFolderAccess: LocalFolderAccess,
) : ViewModel() {

    // ─── Data Model ──────────────────────────────────────────────────────────

    data class NineLivesItem(
        val audioBookId: String,
        val displayTitle: String,
        val displayAuthor: String,
        val coverPath: String?,
        val progressPercent: Double,
        val isMostRecent: Boolean,
        val isDownloaded: Boolean,
        val isBookmarked: Boolean,
        val hoursListened: Double,
        val lifeIndex: Int,       // 0–8
        val lifeLabel: String,    // "LIFE I" … "LIFE IX"
        val weight: String,       // "LIGHT", "MEDIUM", "HEAVY"
        val timeGiven: String,    // Formatted listening time
        val lastPlayedLabel: String, // Relative time ("3h ago")
        val cosmicEnergyColor: Color, // Border color based on hours listened
    )

    data class UiState(
        val lives: List<NineLivesItem> = emptyList(),
        val isLoading: Boolean = false,
        val showEmptyState: Boolean = true,
        val totalListeningTimeText: String = "",
        val totalListeningSeconds: Double = 0.0,
        val connectionStatus: ConnectionStatus = ConnectionStatus.OFFLINE,
        val isLocalMode: Boolean = false,
        val hasAuthToken: Boolean? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    private val reconnectJobOwner = HomeReconnectJobOwner(viewModelScope)

    init {
        // Observe connection status
        viewModelScope.launch {
            connectivityMonitor.connectionStatus.collect { status ->
                _uiState.update { it.copy(connectionStatus = status) }
            }
        }

        // Observe source mode so the status pill can switch to its LOCAL appearance
        viewModelScope.launch {
            settingsManager.settings
                .map { it.appMode == AppMode.LOCAL }
                .distinctUntilChanged()
                .collect { isLocal ->
                    _uiState.update { it.copy(isLocalMode = isLocal) }
                }
        }

        // Session state is independent of server reachability. A reachable
        // /ping endpoint cannot make a signed-out ABS session usable.
        viewModelScope.launch {
            // Do not publish the StateFlow's construction-time false before
            // encrypted storage has had a chance to restore a saved session.
            settingsManager.loadSettings()
            settingsManager.hasAuthToken
                .collect { hasAuthToken ->
                    _uiState.update { it.copy(hasAuthToken = hasAuthToken) }
                }
        }

        // Observe selected source and auth state. Cached server rows are useful
        // offline only when a real configured session still exists. An orphaned
        // token or blank server URL must not make Home impersonate a login.
        viewModelScope.launch {
            combine(
                settingsManager.settings,
                settingsManager.hasAuthToken,
            ) { settings, hasAuthToken -> settings to hasAuthToken }
                .distinctUntilChanged()
                .collectLatest { (settings, hasAuthToken) ->
                    val libraryId = settings.activeLibraryId
                    if (libraryId != null && canShowHomeBooks(settings, hasAuthToken)) {
                        audioBookDao.observeRecentlyPlayedByLibrary(libraryId, 9)
                            .collect { results -> processRecentlyPlayed(results) }
                    } else {
                        processRecentlyPlayed(emptyList())
                    }
                }
        }
    }

    /** Force reload from database. */
    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val libraryId = settingsManager.currentSettings.activeLibraryId
                val results = if (
                    libraryId != null && canShowHomeBooks(
                        settingsManager.currentSettings,
                        settingsManager.hasAuthToken.value,
                    )
                ) {
                    audioBookDao.getRecentlyPlayedByLibrary(libraryId, 9)
                } else {
                    emptyList()
                }
                processRecentlyPlayed(results)
            } catch (e: Exception) {
                _uiState.update { it.copy(showEmptyState = true) }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun reconnect() {
        val state = _uiState.value
        if (
            !isHomeReconnectAvailable(
                isLocalMode = state.isLocalMode,
                hasAuthToken = settingsManager.hasAuthToken.value,
                connectionStatus = state.connectionStatus,
            )
        ) return

        reconnectJobOwner.joinOrStart {
            performHomeReconnect(
                isAudiobookshelfMode = {
                    settingsManager.currentSettings.appMode == AppMode.AUDIOBOOKSHELF
                },
                refreshIsOnline = connectivityMonitor::refreshIsOnlineFromSystem,
                syncNow = syncManager::syncNow,
            )
        }
    }

    /**
     * Secret easter egg: Tap logo 9 times to acknowledge the Vault.
     * The Archive Beneath is always active — this is a lore unlock.
     */
    fun triggerVaultEasterEgg() {
        // The Archive does not toggle. It simply... notices.
        // Future: unlock hidden lore, achievement, or atmospheric event.
    }

    private suspend fun processRecentlyPlayed(results: List<RecentlyPlayedResult>) {
        val settings = settingsManager.currentSettings
        val accessibleLocalIds = if (settings.appMode == AppMode.LOCAL) {
            settings.activeLibraryId
                ?.let { libraryRepository.getById(it) }
                ?.let { localFolderAccess.accessibleLibraryIds(listOf(it)) }
                ?: emptySet()
        } else {
            emptySet()
        }
        val lives = results.mapIndexed { idx, result ->
            val book = result.audioBook
            val durationHours = book.durationSeconds / 3600.0
            val currentTimeHours = book.currentTimeSeconds / 3600.0
            
            // Normalize progress to 0-100 range (API can return 0-1 or 0-100)
            val validProgress = book.progress.coerceAtLeast(0.0)
            val normalizedProgress = if (validProgress <= 1.0) validProgress * 100.0 else validProgress

            NineLivesItem(
                audioBookId = book.id,
                displayTitle = book.title,
                displayAuthor = book.author ?: "Unknown Author",
                coverPath = book.effectiveCoverPath,
                progressPercent = normalizedProgress.coerceIn(0.0, 100.0),
                isMostRecent = idx == 0,
                isDownloaded = book.isDownloaded == 1 &&
                    (book.isLocal == 0 || book.libraryId in accessibleLocalIds),
                isBookmarked = false, // TODO: wire to bookmark data when available
                hoursListened = currentTimeHours,
                lifeIndex = idx,
                lifeLabel = "LIFE ${toRoman(idx + 1)}",
                weight = when {
                    durationHours < 4 -> "LIGHT"
                    durationHours < 15 -> "MEDIUM"
                    else -> "HEAVY"
                },
                timeGiven = formatListeningTime(book.currentTimeSeconds),
                lastPlayedLabel = formatRelativeTime(result.lastPlayedAt),
                cosmicEnergyColor = getCosmicEnergyColor(currentTimeHours),
            )
        }

        // Aggregate total listening time
        val totalSeconds = results.sumOf { it.audioBook.currentTimeSeconds }
        val totalText = formatListeningTime(totalSeconds)

        _uiState.update {
            it.copy(
                lives = lives,
                showEmptyState = lives.isEmpty(),
                totalListeningTimeText = totalText,
                totalListeningSeconds = totalSeconds,
            )
        }
    }

    // ─── Roman Numerals ──────────────────────────────────────────────────────

    private val romanNumerals = arrayOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX")

    private fun toRoman(number: Int): String =
        if (number in 1..romanNumerals.size) romanNumerals[number - 1] else number.toString()

    // ─── Cosmic Energy Color (Gold Spectrum) ─────────────────────────────────
    // Progression: dim gray → nebula teal → sigil gold → brilliant white-gold

    private data class ColorStop(val hours: Double, val r: Int, val g: Int, val b: Int)

    private val colorStops = listOf(
        ColorStop(0.0, 0x4A, 0x4A, 0x4A),   // Dim gray
        ColorStop(1.0, 0x2C, 0x5F, 0x6E),   // NebulaLight — first glow
        ColorStop(5.0, 0x1A, 0x3A, 0x4A),   // NebulaMid — deeper teal
        ColorStop(10.0, 0x8A, 0x73, 0x39),  // SigilGoldDim — muted gold
        ColorStop(25.0, 0xC5, 0xA5, 0x5A),  // SigilGold — primary gold
        ColorStop(50.0, 0xD4, 0xAF, 0x37),  // SigilGoldBright — active gold
        ColorStop(100.0, 0xFF, 0xF0, 0xC8), // Brilliant white-gold
    )

    private fun getCosmicEnergyColor(hoursListened: Double): Color {
        if (hoursListened <= 0) {
            return Color(0xFF4A4A4A)
        }
        if (hoursListened >= colorStops.last().hours) {
            val s = colorStops.last()
            return Color(red = s.r, green = s.g, blue = s.b)
        }
        for (i in 0 until colorStops.size - 1) {
            val a = colorStops[i]
            val b = colorStops[i + 1]
            if (hoursListened >= a.hours && hoursListened < b.hours) {
                val t = ((hoursListened - a.hours) / (b.hours - a.hours)).coerceIn(0.0, 1.0)
                val r = lerp(a.r, b.r, t)
                val g = lerp(a.g, b.g, t)
                val blue = lerp(a.b, b.b, t)
                return Color(red = r, green = g, blue = blue)
            }
        }
        val last = colorStops.last()
        return Color(red = last.r, green = last.g, blue = last.b)
    }

    private fun lerp(a: Int, b: Int, t: Double): Int =
        (a + (b - a) * t).roundToInt().coerceIn(0, 255)

    // ─── Time Formatting ─────────────────────────────────────────────────────

    /** Formats seconds as compact listening time: "< 1m", "45m", "3h 22m" */
    private fun formatListeningTime(totalSeconds: Double): String {
        val totalMinutes = totalSeconds / 60.0
        if (totalMinutes < 1) return "< 1m"
        val totalHours = totalSeconds / 3600.0
        if (totalHours < 1) return "${totalMinutes.toInt()}m"
        val hours = totalHours.toInt()
        val mins = ((totalSeconds % 3600) / 60).toInt()
        return "${hours}h ${mins}m"
    }

    /** Formats an ISO-8601 timestamp as relative time: "3h ago", "Yesterday", etc. */
    private fun formatRelativeTime(timestamp: String?): String {
        if (timestamp.isNullOrEmpty()) return ""
        return try {
            val instant = Instant.parse(timestamp)
            val elapsed = Duration.between(instant, Instant.now())
            val totalMinutes = elapsed.toMinutes()
            val totalHours = elapsed.toHours()
            val totalDays = elapsed.toDays()

            when {
                totalMinutes < 1 -> "Just now"
                totalMinutes < 60 -> "${totalMinutes}m ago"
                totalHours < 24 -> "${totalHours}h ago"
                totalDays < 2 -> "Yesterday"
                totalDays < 7 -> "${totalDays}d ago"
                totalDays < 30 -> "${totalDays / 7}w ago"
                else -> {
                    val dt = java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
                    "${dt.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} ${dt.dayOfMonth}"
                }
            }
        } catch (_: Exception) {
            ""
        }
    }
}

internal fun canShowHomeBooks(
    settings: com.ninelivesaudio.app.domain.model.AppSettings,
    hasAuthToken: Boolean,
): Boolean = when (settings.appMode) {
    AppMode.AUDIOBOOKSHELF ->
        hasAuthToken && settings.serverUrl.isNotBlank() && settings.selectedLibraryId != null
    AppMode.LOCAL -> settings.selectedLocalLibraryId != null
}

internal fun isHomeReconnectAvailable(
    isLocalMode: Boolean,
    hasAuthToken: Boolean,
    connectionStatus: ConnectionStatus,
): Boolean = !isLocalMode && hasAuthToken && (
    connectionStatus == ConnectionStatus.SERVER_UNREACHABLE ||
        connectionStatus == ConnectionStatus.OFFLINE
    )

internal data class HomeConnectionPillState(
    val presentation: ConnectionStatusPresentation,
    val action: HomeConnectionPillAction,
    val actionContentDescription: String?,
)

internal enum class HomeConnectionPillAction { NONE, RECONNECT, OPEN_SETTINGS }

internal fun homeConnectionPillState(uiState: HomeViewModel.UiState): HomeConnectionPillState {
    val presentation = connectionStatusPresentation(
        appMode = if (uiState.isLocalMode) AppMode.LOCAL else AppMode.AUDIOBOOKSHELF,
        hasAuthToken = uiState.hasAuthToken,
        connectionStatus = uiState.connectionStatus,
    )
    val action = when {
        presentation == ConnectionStatusPresentation.SIGNED_OUT ->
            HomeConnectionPillAction.OPEN_SETTINGS
        isHomeReconnectAvailable(
            isLocalMode = uiState.isLocalMode,
            hasAuthToken = uiState.hasAuthToken == true,
            connectionStatus = uiState.connectionStatus,
        ) ->
            HomeConnectionPillAction.RECONNECT
        else -> HomeConnectionPillAction.NONE
    }
    val contentDescription = when (action) {
        HomeConnectionPillAction.OPEN_SETTINGS -> "Signed out. Tap to open Settings."
        HomeConnectionPillAction.RECONNECT -> "Connection lost. Tap to reconnect."
        HomeConnectionPillAction.NONE -> null
    }

    return HomeConnectionPillState(
        presentation = presentation,
        action = action,
        actionContentDescription = contentDescription,
    )
}

internal class HomeReconnectJobOwner(
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private var activeJob: Job? = null

    fun joinOrStart(block: suspend CoroutineScope.() -> Unit): Job = synchronized(lock) {
        activeJob?.takeIf { it.isActive }
            ?: scope.launch(block = block).also { activeJob = it }
    }
}

/**
 * [refreshIsOnline] must re-read the OS network state before [syncNow] runs,
 * not leave the pill's cached isOnline flag stale. Right after connectivity
 * returns (before the OS NetworkCallback lands, or when it never lands at
 * all), that cached flag can still read false with a real network already
 * up, and [syncNow]'s own shouldRunSync pre-check trusts that same flag.
 *
 * The refresh itself makes no network request. It only re-reads OS state,
 * so the single /ping for this whole action is the one [syncNow] already
 * performs internally once its pre-check sees a fresh flag. Probing here
 * too would double the request, letting a flaky second ping suppress a
 * sync the first probe already proved was reachable.
 */
internal suspend fun performHomeReconnect(
    isAudiobookshelfMode: () -> Boolean,
    refreshIsOnline: () -> Unit,
    syncNow: suspend () -> Unit,
) {
    if (!isAudiobookshelfMode()) return
    refreshIsOnline()
    syncNow()
}
