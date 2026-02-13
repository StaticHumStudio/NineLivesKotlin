package com.ninelivesaudio.app.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninelivesaudio.app.domain.model.AudioBook
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playbackManager: PlaybackManager,
    private val connectivityMonitor: ConnectivityMonitor,
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

        // Observe position
        viewModelScope.launch {
            playbackManager.position.collect { pos ->
                val dur = _uiState.value.duration
                val progress = if (dur > Duration.ZERO) {
                    (pos.inWholeMilliseconds.toFloat() / dur.inWholeMilliseconds.toFloat()).coerceIn(0f, 1f)
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
                chapters = book?.chapters?.sortedBy { c -> c.start } ?: emptyList(),
            )
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
        val target = (dur * fraction.toDouble())
        playbackManager.seekTo(target)
    }

    /** Seek within the current chapter by fraction (0–1). */
    fun seekToChapterPosition(fraction: Float) {
        val state = _uiState.value
        val chapter = state.chapters.getOrNull(state.currentChapterIndex) ?: return
        val chapterDur = state.currentChapterDuration
        if (chapterDur <= Duration.ZERO) return

        val targetInChapter = chapterDur * fraction.toDouble()
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
                        it.copy(sleepTimerRemaining = remaining.toLong().let { ms ->
                            (ms / 1000).seconds
                        })
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
