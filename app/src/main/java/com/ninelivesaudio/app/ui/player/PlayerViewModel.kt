package com.ninelivesaudio.app.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninelivesaudio.app.data.repository.BookmarkRepository
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.Bookmark
import com.ninelivesaudio.app.domain.model.Chapter
import com.ninelivesaudio.app.service.ConnectivityMonitor.ConnectionStatus
import com.ninelivesaudio.app.service.ConnectivityMonitor
import com.ninelivesaudio.app.service.PlaybackManager
import com.ninelivesaudio.app.service.PlaybackState
import com.ninelivesaudio.app.service.SettingsManager
import com.ninelivesaudio.app.service.SleepTimerManager
import com.ninelivesaudio.app.domain.util.toClockString
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playbackManager: PlaybackManager,
    private val connectivityMonitor: ConnectivityMonitor,
    private val bookmarkRepository: BookmarkRepository,
    private val settingsManager: SettingsManager,
    private val sleepTimerManager: SleepTimerManager,
) : ViewModel() {

    // ─── UI State ─────────────────────────────────────────────────────────

    data class UiState(
        // Book info
        val hasBook: Boolean = false,
        val title: String = "",
        val author: String = "",
        val coverUrl: String? = null,
        val seriesName: String? = null,
        val currentChapterTitle: String? = null,
        val isLocalFile: Boolean = false,

        // Playback state
        val isPlaying: Boolean = false,
        val isLoading: Boolean = false,

        // Position / Duration
        val position: Duration = Duration.ZERO,
        val duration: Duration = Duration.ZERO,
        val progress: Float = 0f, // 0–1
        val positionText: String = "00:00",
        val durationText: String = "00:00",
        val remainingText: String = "-00:00",

        // Controls
        val speed: Float = 1.0f,
        val volume: Float = 0.8f,

        // EQ
        val eqEnabled: Boolean = false,
        val eqBandGains: List<Int> = List(5) { 0 },
        val eqBandFrequencies: List<Int> = listOf(60, 230, 910, 3600, 14000),
        val eqBandRange: Pair<Int, Int> = Pair(-1500, 1500),
        val volumeBoost: Int = 0, // millibels, 0–1000
        val showEqSheet: Boolean = false,

        // Sleep timer
        val sleepTimerActive: Boolean = false,
        val sleepTimerText: String = "",
        val sleepTimerRemaining: Duration = Duration.ZERO,
        val sleepTimerInGrace: Boolean = false,

        // Chapters
        val chapters: List<Chapter> = emptyList(),
        val currentChapterIndex: Int = -1,
        val currentChapterPosition: Duration = Duration.ZERO,
        val currentChapterDuration: Duration = Duration.ZERO,

        // Bookmarks
        val bookmarks: List<Bookmark> = emptyList(),
        val showBookmarks: Boolean = false,
        val bookmarkItemId: String? = null,

        // Connection
        val connectionStatus: ConnectionStatus = ConnectionStatus.OFFLINE,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    val speedOptions = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
    val sleepTimerOptions = listOf<Int?>(null, 5, 10, 15, 30, 45, 60)

    init {
        // Observe playback state
        viewModelScope.launch {
            playbackManager.playbackState.collect { state ->
                _uiState.update {
                    it.copy(
                        isPlaying = state == PlaybackState.PLAYING,
                        isLoading = state == PlaybackState.LOADING,
                    )
                }
            }
        }

        // Observe current book
        viewModelScope.launch {
            playbackManager.currentBook.collect { book ->
                updateBookProperties(book)
            }
        }

        viewModelScope.launch {
            playbackManager.chapters.collect { chapters ->
                _uiState.update { it.copy(chapters = chapters) }
            }
        }

        // Observe position
        viewModelScope.launch {
            playbackManager.position.collect { pos ->
                val dur = _uiState.value.duration
                val progress = if (dur > Duration.ZERO) {
                    (pos.inWholeMilliseconds.toDouble() / dur.inWholeMilliseconds.toDouble()).coerceIn(0.0, 1.0).toFloat()
                } else 0f

                _uiState.update {
                    it.copy(
                        position = pos,
                        progress = progress,
                        positionText = pos.toClockString(),
                        remainingText = "-${(dur - pos).coerceAtLeast(Duration.ZERO).toClockString()}",
                    )
                }
            }
        }

        // Observe duration
        viewModelScope.launch {
            playbackManager.duration.collect { dur ->
                _uiState.update {
                    it.copy(
                        duration = dur,
                        durationText = dur.toClockString(),
                    )
                }
            }
        }

        // Observe current chapter
        viewModelScope.launch {
            playbackManager.currentChapter.collect { chapter ->
                _uiState.update { it.copy(currentChapterTitle = chapter?.title) }
            }
        }

        viewModelScope.launch {
            playbackManager.currentChapterIndex.collect { idx ->
                _uiState.update { it.copy(currentChapterIndex = idx) }
            }
        }

        // Observe chapter progress
        viewModelScope.launch {
            combine(
                playbackManager.position,
                playbackManager.currentChapter,
            ) { position, chapter ->
                if (chapter != null) {
                    val chapterStart = chapter.startTime
                    val chapterDuration = chapter.duration
                    val chapterPosition = (position - chapterStart).coerceAtLeast(Duration.ZERO)

                    _uiState.update {
                        it.copy(
                            currentChapterPosition = chapterPosition,
                            currentChapterDuration = chapterDuration,
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            currentChapterPosition = Duration.ZERO,
                            currentChapterDuration = Duration.ZERO,
                        )
                    }
                }
            }.collect()
        }

        // Observe speed/volume
        viewModelScope.launch {
            playbackManager.speed.collect { spd ->
                _uiState.update { it.copy(speed = spd) }
            }
        }

        viewModelScope.launch {
            playbackManager.volume.collect { vol ->
                _uiState.update { it.copy(volume = vol) }
            }
        }

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

        viewModelScope.launch {
            playbackManager.volumeBoost.collect { boost ->
                _uiState.update { it.copy(volumeBoost = boost) }
            }
        }

        viewModelScope.launch {
            playbackManager.isLocalFile.collect { local ->
                _uiState.update { it.copy(isLocalFile = local) }
            }
        }

        // Observe sleep timer
        viewModelScope.launch {
            sleepTimerManager.state.collect { timerState ->
                _uiState.update {
                    it.copy(
                        sleepTimerActive = timerState.isActive,
                        sleepTimerRemaining = timerState.remaining,
                        sleepTimerText = formatSleepTimer(timerState),
                        sleepTimerInGrace = timerState.isInGracePeriod,
                    )
                }
            }
        }

        // Observe connection
        viewModelScope.launch {
            connectivityMonitor.connectionStatus.collect { status ->
                _uiState.update { it.copy(connectionStatus = status) }
            }
        }
    }

    private fun formatSleepTimer(state: SleepTimerManager.SleepTimerState): String {
        if (!state.isActive) return ""
        if (state.isInGracePeriod) {
            val graceSecs = state.graceRemaining.inWholeSeconds.coerceAtLeast(0)
            return "Checking motion... ${graceSecs}s"
        }
        val remaining = state.remaining.inWholeMilliseconds.coerceAtLeast(0)
        val mins = remaining / 60_000
        val secs = (remaining % 60_000) / 1_000
        return "Sleep in ${mins}:${secs.toString().padStart(2, '0')}"
    }

    private fun updateBookProperties(book: AudioBook?) {
        _uiState.update {
            it.copy(
                hasBook = book != null,
                title = book?.title ?: "",
                author = book?.author ?: "",
                coverUrl = book?.coverPath,
                seriesName = book?.seriesName,
                bookmarkItemId = book?.id,
            )
        }
        // Load bookmarks when a new book is loaded
        if (book != null) {
            loadBookmarks(book.id)
        } else {
            _uiState.update { it.copy(bookmarks = emptyList()) }
        }
    }

    // ─── Playback Controls ────────────────────────────────────────────────

    fun playPause() {
        if (_uiState.value.isPlaying) {
            playbackManager.pause()
        } else {
            playbackManager.play()
        }
    }

    fun stop() {
        playbackManager.stop()
        cancelSleepTimer()
    }

    fun skipForward() {
        playbackManager.skipForward(30)
    }

    fun skipBackward() {
        playbackManager.skipBackward(10)
    }

    fun seekTo(fraction: Float) {
        val dur = _uiState.value.duration
        if (dur <= Duration.ZERO) return
        val safeFraction = fraction.coerceIn(0f, 1f)
        val target = dur * safeFraction.toDouble()
        playbackManager.seekTo(target)
    }

    /** Seek within the current chapter by fraction (0–1). */
    fun seekToChapterPosition(fraction: Float) {
        val state = _uiState.value
        val chapter = state.chapters.getOrNull(state.currentChapterIndex) ?: return
        val chapterDur = state.currentChapterDuration
        if (chapterDur <= Duration.ZERO) return

        val safeFraction = fraction.coerceIn(0f, 1f)
        val targetInChapter = chapterDur * safeFraction.toDouble()
        val absoluteTarget = chapter.startTime + targetInChapter
        playbackManager.seekTo(absoluteTarget)
    }

    fun setSpeed(speed: Float) {
        playbackManager.setSpeed(speed)
    }

    fun setVolume(volume: Float) {
        playbackManager.setVolume(volume)
    }

    fun setEqEnabled(enabled: Boolean) {
        playbackManager.setEqEnabled(enabled)
        viewModelScope.launch {
            settingsManager.updateSettings { it.copy(eqEnabled = enabled) }
        }
    }

    fun setEqBandGain(band: Int, gainMillibels: Int) {
        playbackManager.setEqBandGain(band, gainMillibels)
        viewModelScope.launch {
            settingsManager.updateSettings { it.copy(eqBandGains = playbackManager.eqBandGains.value) }
        }
    }

    fun setVolumeBoost(gainMb: Int) {
        playbackManager.setVolumeBoost(gainMb)
        viewModelScope.launch {
            settingsManager.updateSettings { it.copy(volumeBoostGain = playbackManager.volumeBoost.value) }
        }
    }

    fun seekToChapter(index: Int) {
        playbackManager.seekToChapter(index)
    }

    // ─── EQ Sheet ──────────────────────────────────────────────────────────

    fun toggleEqSheet() {
        _uiState.update { it.copy(showEqSheet = !it.showEqSheet) }
    }

    fun dismissEqSheet() {
        _uiState.update { it.copy(showEqSheet = false) }
    }

    // ─── Bookmarks ────────────────────────────────────────────────────────

    fun toggleBookmarks() {
        _uiState.update { it.copy(showBookmarks = !it.showBookmarks) }
    }

    fun dismissBookmarks() {
        _uiState.update { it.copy(showBookmarks = false) }
    }

    private fun loadBookmarks(itemId: String) {
        viewModelScope.launch {
            try {
                val bookmarks = bookmarkRepository.getBookmarks(itemId)
                _uiState.update { it.copy(bookmarks = bookmarks) }
            } catch (_: Exception) {
                _uiState.update { it.copy(bookmarks = emptyList()) }
            }
        }
    }

    fun addBookmark(title: String) {
        val itemId = _uiState.value.bookmarkItemId ?: return
        val currentTimeSeconds = _uiState.value.position.toDouble(kotlin.time.DurationUnit.SECONDS)

        viewModelScope.launch {
            val success = bookmarkRepository.createBookmark(itemId, title, currentTimeSeconds)
            if (success) {
                loadBookmarks(itemId)
            }
        }
    }

    fun deleteBookmark(bookmark: Bookmark) {
        val itemId = _uiState.value.bookmarkItemId ?: return

        viewModelScope.launch {
            val success = bookmarkRepository.deleteBookmark(itemId, bookmark.time)
            if (success) {
                loadBookmarks(itemId)
            }
        }
    }

    fun seekToBookmark(bookmark: Bookmark) {
        val targetPosition = bookmark.time.seconds
        playbackManager.seekTo(targetPosition)
    }

    // ─── Sleep Timer ──────────────────────────────────────────────────────

    fun setSleepTimer(minutes: Int?) {
        if (minutes == null || minutes <= 0) {
            sleepTimerManager.cancel()
        } else {
            sleepTimerManager.start(minutes)
        }
    }

    fun cancelSleepTimer() = sleepTimerManager.cancel()

}
