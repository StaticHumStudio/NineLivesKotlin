package com.ninelivesaudio.app.ui.bookdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninelivesaudio.app.data.local.converter.toDomain
import com.ninelivesaudio.app.data.local.dao.DownloadItemDao
import com.ninelivesaudio.app.data.remote.ApiService
import com.ninelivesaudio.app.data.repository.AudioBookRepository
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.Chapter
import com.ninelivesaudio.app.domain.model.DownloadStatus
import com.ninelivesaudio.app.domain.model.ListeningSession
import com.ninelivesaudio.app.service.DownloadManager
import com.ninelivesaudio.app.service.PlaybackManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlin.time.Duration

@HiltViewModel
class BookDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val audioBookRepository: AudioBookRepository,
    private val playbackManager: PlaybackManager,
    private val downloadManager: DownloadManager,
    private val downloadItemDao: DownloadItemDao,
    private val apiService: ApiService,
) : ViewModel() {

    private val bookId: String = savedStateHandle["bookId"] ?: ""

    /** Download button state shown on the detail screen. */
    enum class DownloadButtonState {
        NONE,         // Not downloaded, not in progress
        QUEUED,       // Queued, waiting to start
        DOWNLOADING,  // Actively downloading
        PAUSED,       // Download paused
        COMPLETED,    // Fully downloaded
    }

    data class UiState(
        val isLoading: Boolean = true,
        val book: AudioBook? = null,
        val title: String = "",
        val author: String = "",
        val narrator: String? = null,
        val seriesDisplay: String? = null,
        val description: String? = null,
        val coverUrl: String? = null,
        val duration: Duration = Duration.ZERO,
        val progress: Double = 0.0,
        val progressPercent: Int = 0,
        val hasProgress: Boolean = false,
        val isFinished: Boolean = false,
        val isDownloaded: Boolean = false,
        val chapters: List<Chapter> = emptyList(),
        val addedAt: Long? = null,
        val errorMessage: String? = null,
        val downloadState: DownloadButtonState = DownloadButtonState.NONE,
        val downloadProgress: Int = 0, // 0-100
        val listeningSessions: List<ListeningSession> = emptyList(),
        val isHistoryExpanded: Boolean = false,
        val isHistoryLoading: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        if (bookId.isNotEmpty()) {
            loadBook()
            observeDownloadState()
            observeDownloadProgress()
        }
    }

    private fun loadBook() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            try {
                // Load from local DB
                var book = audioBookRepository.getById(bookId)

                // If not found locally, try server
                if (book == null) {
                    book = audioBookRepository.fetchFromServer(bookId)
                    if (book != null) {
                        audioBookRepository.save(book)
                    }
                }

                if (book == null) {
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = "Book not found")
                    }
                    return@launch
                }

                populateFromBook(book)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = "Failed to load: ${e.message}")
                }
            }
        }
    }

    private fun populateFromBook(book: AudioBook) {
        val seriesDisplay = if (!book.seriesName.isNullOrEmpty()) {
            if (book.seriesSequence.isNullOrEmpty()) {
                book.seriesName
            } else {
                "${book.seriesName} #${book.seriesSequence}"
            }
        } else null

        _uiState.update {
            it.copy(
                isLoading = false,
                book = book,
                title = book.title,
                author = book.author,
                narrator = book.narrator,
                seriesDisplay = seriesDisplay,
                description = book.description,
                coverUrl = book.coverPath,
                duration = book.duration,
                progress = book.progress,
                progressPercent = book.progressPercent.roundToInt(),
                hasProgress = book.hasProgress,
                isFinished = book.isFinished,
                isDownloaded = book.isDownloaded,
                chapters = book.chapters.sortedBy { c -> c.start },
                addedAt = book.addedAt,
                downloadState = if (book.isDownloaded) DownloadButtonState.COMPLETED else it.downloadState,
            )
        }
    }

    // ─── Download State Observation ──────────────────────────────────────────

    private fun observeDownloadState() {
        viewModelScope.launch {
            downloadItemDao.observeAll().collect { entities ->
                val entity = entities.firstOrNull { it.audioBookId == bookId }
                val state = if (entity == null) {
                    if (_uiState.value.isDownloaded) DownloadButtonState.COMPLETED
                    else DownloadButtonState.NONE
                } else {
                    val item = entity.toDomain()
                    when (item.status) {
                        DownloadStatus.Queued -> DownloadButtonState.QUEUED
                        DownloadStatus.Downloading -> DownloadButtonState.DOWNLOADING
                        DownloadStatus.Paused -> DownloadButtonState.PAUSED
                        DownloadStatus.Completed -> DownloadButtonState.COMPLETED
                        DownloadStatus.Failed -> DownloadButtonState.NONE
                        DownloadStatus.Cancelled -> DownloadButtonState.NONE
                    }
                }
                _uiState.update { it.copy(downloadState = state) }
            }
        }
    }

    private fun observeDownloadProgress() {
        viewModelScope.launch {
            downloadManager.progressUpdates.collect { progress ->
                val entity = downloadItemDao.getByAudioBookId(bookId)
                if (entity != null && entity.id == progress.downloadId) {
                    val pct = if (progress.totalBytes > 0) {
                        (progress.downloadedBytes.toDouble() / progress.totalBytes * 100).toInt().coerceIn(0, 100)
                    } else 0
                    _uiState.update { it.copy(downloadProgress = pct) }
                }
            }
        }

        viewModelScope.launch {
            downloadManager.downloadCompleted.collect { completedItem ->
                if (completedItem.audioBookId == bookId) {
                    _uiState.update {
                        it.copy(
                            isDownloaded = true,
                            downloadState = DownloadButtonState.COMPLETED,
                            downloadProgress = 100,
                        )
                    }
                }
            }
        }
    }

    // ─── Actions ─────────────────────────────────────────────────────────────

    fun refresh() {
        loadBook()
    }

    /**
     * Load this book into PlaybackManager and start playback.
     * Called when user taps Play/Continue.
     * [onReady] is invoked after the book is loaded (navigate to Player).
     */
    fun playBook(onReady: () -> Unit) {
        val book = _uiState.value.book ?: return
        viewModelScope.launch {
            val loaded = playbackManager.loadAudioBook(book)
            if (loaded) {
                onReady()
            }
        }
    }

    /** Queue this book for download. */
    fun downloadBook() {
        val book = _uiState.value.book ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(downloadState = DownloadButtonState.QUEUED, downloadProgress = 0) }
            downloadManager.queueDownload(book)
        }
    }

    /** Delete the downloaded files for this book. */
    fun deleteDownload() {
        viewModelScope.launch {
            downloadManager.deleteDownload(bookId)
            _uiState.update {
                it.copy(
                    isDownloaded = false,
                    downloadState = DownloadButtonState.NONE,
                    downloadProgress = 0,
                )
            }
        }
    }

    // ─── Listening History ────────────────────────────────────────────────────

    fun toggleHistoryExpanded() {
        val wasExpanded = _uiState.value.isHistoryExpanded
        _uiState.update { it.copy(isHistoryExpanded = !wasExpanded) }
        if (!wasExpanded && _uiState.value.listeningSessions.isEmpty() && !_uiState.value.isHistoryLoading) {
            loadListeningSessions()
        }
    }

    private fun loadListeningSessions() {
        viewModelScope.launch {
            _uiState.update { it.copy(isHistoryLoading = true) }
            try {
                val sessions = apiService.getListeningSessions(bookId)
                _uiState.update {
                    it.copy(listeningSessions = sessions, isHistoryLoading = false)
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(isHistoryLoading = false) }
            }
        }
    }

    /**
     * Load this book, seek to the session's position, and navigate to the player.
     */
    fun jumpToSession(session: ListeningSession, onReady: () -> Unit) {
        val book = _uiState.value.book ?: return
        viewModelScope.launch {
            val loaded = playbackManager.loadAudioBook(book)
            if (loaded) {
                playbackManager.seekTo(session.currentTime)
                onReady()
            }
        }
    }
}
