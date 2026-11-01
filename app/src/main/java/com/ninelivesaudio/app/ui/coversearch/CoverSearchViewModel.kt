package com.ninelivesaudio.app.ui.coversearch

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninelivesaudio.app.data.repository.AudioBookRepository
import com.ninelivesaudio.app.service.cover.AudiobookCoversSource
import com.ninelivesaudio.app.service.cover.CoverDownloader
import com.ninelivesaudio.app.service.cover.CoverPicker
import com.ninelivesaudio.app.service.cover.CoverResult
import com.ninelivesaudio.app.service.cover.CoverSaveResult
import com.ninelivesaudio.app.service.cover.CoverSearchPhase
import com.ninelivesaudio.app.service.cover.OpenLibraryCoverSource
import com.ninelivesaudio.app.service.cover.normalizedSearchText
import com.ninelivesaudio.app.service.cover.prefillSearchText
import com.ninelivesaudio.app.service.cover.runCoverSearch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface CoverPreview {
    data class FromSearch(val result: CoverResult) : CoverPreview
    data class FromPhoto(val uri: Uri) : CoverPreview
}

data class CoverSearchUiState(
    val bookTitle: String = "",
    val bookAuthor: String? = null,
    val bookCover: String? = null,
    val query: String = "",
    val phase: Phase = Phase.IDLE,
    val results: List<CoverResult> = emptyList(),
    val preview: CoverPreview? = null,
    val saving: Boolean = false,
    val notice: String? = null,
    val done: Boolean = false,
) {
    enum class Phase { IDLE, SEARCHING, RESULTS, NO_MATCHES, UNREACHABLE }
}

/**
 * Find a cover for one local book. Opening the screen sends nothing: the field starts
 * with the book's title and author, and only the Search button asks the cover services.
 */
@HiltViewModel
class CoverSearchViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: AudioBookRepository,
    audiobookCovers: AudiobookCoversSource,
    openLibrary: OpenLibraryCoverSource,
    private val downloader: CoverDownloader,
    private val picker: CoverPicker,
) : ViewModel() {

    /** Every source, asked in parallel once per Search tap. Add a source here. */
    private val sources = listOf(audiobookCovers, openLibrary)

    private val bookId: String = savedStateHandle.get<String>("bookId").orEmpty()

    private val _uiState = MutableStateFlow(CoverSearchUiState())
    val uiState: StateFlow<CoverSearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            val book = repository.getById(bookId) ?: return@launch
            _uiState.update {
                it.copy(
                    bookTitle = book.title,
                    bookAuthor = book.author,
                    bookCover = book.effectiveCoverPath,
                    // Only fill an untouched box, so a fast typist keeps what they typed.
                    query = if (it.query.isEmpty()) prefillSearchText(book.title, book.author) else it.query,
                )
            }
        }
    }

    fun onQueryChange(text: String) {
        if (_uiState.value.saving) return
        _uiState.update { it.copy(query = text) }
    }

    fun search() {
        val state = _uiState.value
        if (state.saving) return
        val text = normalizedSearchText(state.query) ?: return
        searchJob?.cancel()
        _uiState.update { it.copy(phase = CoverSearchUiState.Phase.SEARCHING, results = emptyList(), notice = null) }
        searchJob = viewModelScope.launch {
            val outcome = runCoverSearch(text, sources, downloader::thumbnail)
            val phase = when (outcome.phase) {
                CoverSearchPhase.RESULTS -> CoverSearchUiState.Phase.RESULTS
                CoverSearchPhase.NO_MATCHES -> CoverSearchUiState.Phase.NO_MATCHES
                CoverSearchPhase.UNREACHABLE -> CoverSearchUiState.Phase.UNREACHABLE
            }
            _uiState.update { it.copy(phase = phase, results = outcome.results) }
        }
    }

    fun choose(result: CoverResult) {
        if (_uiState.value.saving) return
        _uiState.update { it.copy(preview = CoverPreview.FromSearch(result), notice = null) }
    }

    fun choosePhoto(uri: Uri) {
        if (_uiState.value.saving) return
        _uiState.update { it.copy(preview = CoverPreview.FromPhoto(uri), notice = null) }
    }

    fun dismissPreview() {
        if (_uiState.value.saving) return
        _uiState.update { it.copy(preview = null) }
    }

    fun confirm() {
        val state = _uiState.value
        if (state.saving) return
        val preview = state.preview ?: return
        _uiState.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = when (preview) {
                is CoverPreview.FromSearch -> picker.saveFromSearch(bookId, preview.result)
                is CoverPreview.FromPhoto -> picker.saveFromPhoto(bookId, preview.uri)
            }
            _uiState.update {
                it.copy(
                    saving = false,
                    preview = null,
                    done = result == CoverSaveResult.SAVED,
                    notice = if (result == CoverSaveResult.SAVED) null else CoverCopy.SAVE_FAILED,
                )
            }
        }
    }
}
