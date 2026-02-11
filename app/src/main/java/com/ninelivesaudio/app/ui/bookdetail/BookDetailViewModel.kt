package com.ninelivesaudio.app.ui.bookdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninelivesaudio.app.data.repository.AudioBookRepository
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.Chapter
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
) : ViewModel() {

    private val bookId: String = savedStateHandle["bookId"] ?: ""

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
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        if (bookId.isNotEmpty()) {
            loadBook()
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
            )
        }
    }

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
                playbackManager.play()
                onReady()
            }
        }
    }
}
