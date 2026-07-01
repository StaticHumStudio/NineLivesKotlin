package com.ninelivesaudio.app.ui.bookdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninelivesaudio.app.data.local.converter.toDomain
import com.ninelivesaudio.app.data.local.dao.DownloadItemDao
import com.ninelivesaudio.app.data.repository.AudioBookRepository
import com.ninelivesaudio.app.data.repository.ListeningSessionRepository
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
    private val listeningSessionRepository: ListeningSessionRepository,
) : ViewModel() {

    private val bookId: String = savedStateHandle["bookId"] ?: ""

    // The active download row's id for this book, maintained reactively by
    // observeDownloadState (Room is the source of truth). Progress events are
    // matched against it so a replayed/late event from a cancelled or superseded
    // attempt for the same book can't bleed into a fresh attempt. Both collectors
    // run on viewModelScope's main dispatcher, so no synchronization is needed.
    private var currentDownloadId: String? = null

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
        val isLocal: Boolean = false,
        val isArchived: Boolean = false,
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
                coverUrl = book.effectiveCoverPath,
                duration = book.duration,
                progress = book.progress,
                progressPercent = book.progressPercent.roundToInt(),
                hasProgress = book.hasProgress,
                isFinished = book.isFinished,
                isDownloaded = book.isDownloaded,
                isLocal = book.isLocal,
                isArchived = book.isArchived,
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
                currentDownloadId = entity?.id
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
                // Match the current download row's id (cached by observeDownloadState)
                // instead of querying the DB every tick (~10x/sec). Comparing the id
                // — not just audioBookId — rejects a replayed/late event from a
                // cancelled or superseded attempt for the same book.
                if (progress.downloadId == currentDownloadId) {
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
        // Backstop symmetric with jumpToSession: the Play button is already
        // disabled for archived books, but never load a missing source.
        if (_uiState.value.isArchived) return
        viewModelScope.launch {
            val loaded = playbackManager.loadAudioBook(book)
            if (loaded) {
                onReady()
            }
        }
    }

    /** Queue this book for download. No-op for scanned-local books. */
    fun downloadBook() {
        val book = _uiState.value.book ?: return
        if (book.isLocal) return
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
                val sessions = listeningSessionRepository.getSessionsForBook(bookId)
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
        // An archived book's source file is gone; jumping would try to load a
        // missing URI. History stays visible (read-only), so tapping a row is a
        // no-op rather than a doomed load.
        if (_uiState.value.isArchived) return
        viewModelScope.launch {
            val loaded = playbackManager.loadAudioBook(book)
            if (loaded) {
                playbackManager.seekTo(session.currentTime)
                onReady()
            }
        }
    }

    /**
     * Permanently delete this (archived) book and everything tied to it, then
     * invoke [onDeleted] to leave the screen. Cascade wired in Task 7.
     */
    fun deleteForever(onDeleted: () -> Unit) {
        val id = _uiState.value.book?.id ?: return
        viewModelScope.launch {
            // Stop playback first if this is the live book, so the session-sync
            // coroutine can't re-write the rows we're about to delete.
            if (playbackManager.currentBook.value?.id == id) {
                playbackManager.stop()
            }
            audioBookRepository.deleteLocalBookForever(id)
            onDeleted()
        }
    }
}

/**
 * Only an archived book blocks playback — its source file is gone. Everything
 * else plays: a scanned-local book from its file, a downloaded remote book from
 * disk, and a normal server book streams on demand (isLocal=false,
 * isDownloaded=false is the common Audiobookshelf online case).
 */
internal fun canPlayBook(isArchived: Boolean): Boolean = !isArchived
