package com.ninelivesaudio.app.ui.home

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninelivesaudio.app.data.local.dao.AudioBookDao
import com.ninelivesaudio.app.data.local.entity.RecentlyPlayedResult
import com.ninelivesaudio.app.service.ConnectivityMonitor
import com.ninelivesaudio.app.service.ConnectivityMonitor.ConnectionStatus
import com.ninelivesaudio.app.service.SettingsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val audioBookDao: AudioBookDao,
    private val connectivityMonitor: ConnectivityMonitor,
    private val settingsManager: SettingsManager,
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
        val connectionStatus: ConnectionStatus = ConnectionStatus.OFFLINE,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        // Observe connection status
        viewModelScope.launch {
            connectivityMonitor.connectionStatus.collect { status ->
                _uiState.update { it.copy(connectionStatus = status) }
            }
        }

        // Observe recently played reactively — auto-updates when progress changes
        viewModelScope.launch {
            audioBookDao.observeRecentlyPlayed(9).collect { results ->
                processRecentlyPlayed(results)
            }
        }
    }

    /** Force reload from database. */
    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val results = audioBookDao.getRecentlyPlayed(9)
                processRecentlyPlayed(results)
            } catch (e: Exception) {
                _uiState.update { it.copy(showEmptyState = true) }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
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

    private fun processRecentlyPlayed(results: List<RecentlyPlayedResult>) {
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
                coverPath = book.coverPath,
                progressPercent = normalizedProgress.coerceIn(0.0, 100.0),
                isMostRecent = idx == 0,
                isDownloaded = book.isDownloaded == 1,
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
            return Color(0xFF000000 or (s.r.toLong() shl 16) or (s.g.toLong() shl 8) or s.b.toLong())
        }
        for (i in 0 until colorStops.size - 1) {
            val a = colorStops[i]
            val b = colorStops[i + 1]
            if (hoursListened >= a.hours && hoursListened < b.hours) {
                val t = ((hoursListened - a.hours) / (b.hours - a.hours)).coerceIn(0.0, 1.0)
                val r = lerp(a.r, b.r, t)
                val g = lerp(a.g, b.g, t)
                val blue = lerp(a.b, b.b, t)
                return Color(0xFF000000 or (r.toLong() shl 16) or (g.toLong() shl 8) or blue.toLong())
            }
        }
        val last = colorStops.last()
        return Color(0xFF000000 or (last.r.toLong() shl 16) or (last.g.toLong() shl 8) or last.b.toLong())
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
        } catch (e: DateTimeParseException) {
            ""
        }
    }
}
