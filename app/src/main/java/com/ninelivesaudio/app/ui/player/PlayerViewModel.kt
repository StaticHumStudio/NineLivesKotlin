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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playbackManager: PlaybackManager,
    private val connectivityMonitor: ConnectivityMonitor,
    private val bookmarkRepository: BookmarkRepository,
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

        // Sleep timer
        val sleepTimerActive: Boolean = false,
        val sleepTimerText: String = "",
        val sleepTimerRemaining: Duration = Duration.ZERO,

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

    // Sleep timer
    private var sleepTimerJob: Job? = null
    private var sleepTimerEndTime: Long? = null

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
                        positionText = formatDuration(pos),
                        remainingText = "-${formatDuration((dur - pos).coerceAtLeast(Duration.ZERO))}",
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
                        durationText = formatDuration(dur),
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
            playbackManager.isLocalFile.collect { local ->
                _uiState.update { it.copy(isLocalFile = local) }
            }
        }

        // Observe connection
        viewModelScope.launch {
            connectivityMonitor.connectionStatus.collect { status ->
                _uiState.update { it.copy(connectionStatus = status) }
            }
        }
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

    fun seekToChapter(index: Int) {
        playbackManager.seekToChapter(index)
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
        cancelSleepTimer()

        if (minutes == null || minutes <= 0) {
            _uiState.update {
                it.copy(sleepTimerActive = false, sleepTimerText = "")
            }
            return
        }

        sleepTimerEndTime = System.currentTimeMillis() + minutes * 60_000L
        _uiState.update {
            it.copy(
                sleepTimerActive = true,
                sleepTimerRemaining = minutes.minutes,
            )
        }
        updateSleepTimerText()

        sleepTimerJob = viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                val endTime = sleepTimerEndTime ?: break
                val remaining = endTime - System.currentTimeMillis()

                if (remaining <= 0) {
                    playbackManager.pause()
                    cancelSleepTimer()
                    break
                } else {
                    _uiState.update {
                        it.copy(sleepTimerRemaining = remaining.milliseconds)
                    }
                    updateSleepTimerText()
                }
            }
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        sleepTimerEndTime = null
        _uiState.update {
            it.copy(
                sleepTimerActive = false,
                sleepTimerText = "",
                sleepTimerRemaining = Duration.ZERO,
            )
        }
    }

    private fun updateSleepTimerText() {
        val endTime = sleepTimerEndTime ?: return
        val remaining = (endTime - System.currentTimeMillis()).coerceAtLeast(0)
        val mins = remaining / 60_000
        val secs = (remaining % 60_000) / 1_000
        _uiState.update {
            it.copy(sleepTimerText = "Sleep in ${mins}:${secs.toString().padStart(2, '0')}")
        }
    }

    // ─── Formatting ───────────────────────────────────────────────────────

    private fun formatDuration(duration: Duration): String {
        val totalSeconds = duration.inWholeSeconds
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return if (hours > 0) {
            "%02d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }
}
