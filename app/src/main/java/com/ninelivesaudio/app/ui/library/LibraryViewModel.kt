package com.ninelivesaudio.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninelivesaudio.app.data.remote.ApiService
import com.ninelivesaudio.app.data.repository.AudioBookRepository
import com.ninelivesaudio.app.data.repository.LibraryRepository
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.Library
import com.ninelivesaudio.app.service.ConnectivityMonitor
import com.ninelivesaudio.app.service.ConnectivityMonitor.ConnectionStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

enum class ViewMode { ALL, SERIES, AUTHOR, GENRE }
enum class SortMode { DEFAULT, TITLE, AUTHOR, PROGRESS, RECENT_PROGRESS }

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val audioBookRepository: AudioBookRepository,
    private val apiService: ApiService,
    private val connectivityMonitor: ConnectivityMonitor,
) : ViewModel() {

    // ─── UI State ─────────────────────────────────────────────────────────

    data class UiState(
        val isLoading: Boolean = false,
        val isRefreshing: Boolean = false,
        val libraries: List<Library> = emptyList(),
        val selectedLibrary: Library? = null,
        val allBooks: List<AudioBook> = emptyList(),
        val filteredBooks: List<AudioBook> = emptyList(),
        val searchQuery: String = "",
        val viewMode: ViewMode = ViewMode.ALL,
        val sortMode: SortMode = SortMode.DEFAULT,
        val selectedGroupFilter: String? = null,
        val availableGroups: List<String> = emptyList(),
        val hideFinished: Boolean = false,
        val showDownloadedOnly: Boolean = false,
        val connectionStatus: ConnectionStatus = ConnectionStatus.OFFLINE,
        val errorMessage: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // Search debounce
    private var searchJob: Job? = null

    init {
        // Observe connectivity and auto-filter to downloaded when offline
        viewModelScope.launch {
            connectivityMonitor.connectionStatus.collect { status ->
                val wasConnected = _uiState.value.connectionStatus == ConnectionStatus.CONNECTED
                val isNowOffline = status == ConnectionStatus.OFFLINE || status == ConnectionStatus.SERVER_UNREACHABLE

                _uiState.update { it.copy(connectionStatus = status) }

                // Auto-enable "Downloaded Only" when going offline or server becomes unreachable
                if (wasConnected && isNowOffline && !_uiState.value.showDownloadedOnly) {
                    _uiState.update { it.copy(showDownloadedOnly = true) }
                    applyFilter()
                }
            }
        }

        // Initial load
        viewModelScope.launch {
            loadLibraries()
        }
    }

    // ─── Loading ──────────────────────────────────────────────────────────

    private suspend fun loadLibraries() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }

        try {
            // Load local first
            var libs = libraryRepository.getAll()

            // Sync from server if possible
            try {
                val serverLibs = libraryRepository.syncFromServer()
                if (serverLibs.isNotEmpty()) libs = serverLibs
            } catch (_: Exception) {
                // Use cached
            }

            val selected = _uiState.value.selectedLibrary
                ?: libs.firstOrNull()

            _uiState.update {
                it.copy(
                    libraries = libs,
                    selectedLibrary = selected,
                )
            }

            if (selected != null) {
                loadAudioBooks(selected.id)
            }
        } catch (e: Exception) {
            _uiState.update {
                it.copy(errorMessage = "Failed to load libraries: ${e.message}")
            }
        } finally {
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    private suspend fun loadAudioBooks(libraryId: String) {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }

        try {
            // Load local first
            var books = audioBookRepository.getByLibrary(libraryId)

            // Sync from server
            try {
                val serverBooks = audioBookRepository.syncLibraryItems(libraryId)
                if (serverBooks.isNotEmpty()) {
                    books = audioBookRepository.getByLibrary(libraryId)
                }
            } catch (_: Exception) {
                // Use cached
            }

            _uiState.update { it.copy(allBooks = books) }
            updateAvailableGroups()
            applyFilter()
        } catch (e: Exception) {
            _uiState.update {
                it.copy(errorMessage = "Failed to load audiobooks: ${e.message}")
            }
        } finally {
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    // ─── User Actions ─────────────────────────────────────────────────────

    fun onLibrarySelected(library: Library) {
        _uiState.update { it.copy(selectedLibrary = library, searchQuery = "") }
        viewModelScope.launch { loadAudioBooks(library.id) }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        // Debounce search
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)
            applyFilter()
        }
    }

    fun onViewModeChanged(mode: ViewMode) {
        _uiState.update { it.copy(viewMode = mode, selectedGroupFilter = null) }
        updateAvailableGroups()
        applyFilter()
    }

    fun onSortModeChanged(mode: SortMode) {
        _uiState.update { it.copy(sortMode = mode) }
        applyFilter()
    }

    fun onGroupFilterSelected(group: String?) {
        _uiState.update { it.copy(selectedGroupFilter = group) }
        applyFilter()
    }

    fun onHideFinishedChanged(value: Boolean) {
        _uiState.update { it.copy(hideFinished = value) }
        applyFilter()
    }

    fun onShowDownloadedOnlyChanged(value: Boolean) {
        _uiState.update { it.copy(showDownloadedOnly = value) }
        applyFilter()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            loadLibraries()
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    // ─── Filter/Sort Logic ────────────────────────────────────────────────

    private fun updateAvailableGroups() {
        val state = _uiState.value
        val groups = when (state.viewMode) {
            ViewMode.SERIES -> state.allBooks
                .mapNotNull { it.seriesName }
                .filter { it.isNotEmpty() }
                .distinct()
                .sorted()

            ViewMode.AUTHOR -> state.allBooks
                .map { it.author }
                .filter { it.isNotEmpty() }
                .distinct()
                .sorted()

            ViewMode.GENRE -> state.allBooks
                .flatMap { it.genres }
                .filter { it.isNotEmpty() }
                .distinct()
                .sorted()

            ViewMode.ALL -> emptyList()
        }
        _uiState.update { it.copy(availableGroups = groups) }
    }

    private fun applyFilter() {
        val state = _uiState.value
        var filtered = state.allBooks.asSequence()

        // Library filter
        val libraryId = state.selectedLibrary?.id
        if (libraryId != null) {
            filtered = filtered.filter { it.libraryId == libraryId }
        }

        // View mode group filter
        if (state.viewMode != ViewMode.ALL && !state.selectedGroupFilter.isNullOrEmpty()) {
            filtered = when (state.viewMode) {
                ViewMode.SERIES -> filtered.filter { it.seriesName == state.selectedGroupFilter }
                ViewMode.AUTHOR -> filtered.filter { it.author == state.selectedGroupFilter }
                ViewMode.GENRE -> filtered.filter { state.selectedGroupFilter in it.genres }
                else -> filtered
            }
        }

        // Hide finished
        if (state.hideFinished) {
            filtered = filtered.filter { !it.isFinished && it.progress < 1.0 }
        }

        // Downloaded only
        if (state.showDownloadedOnly) {
            filtered = filtered.filter { it.isDownloaded }
        }

        // Search
        if (state.searchQuery.isNotBlank()) {
            val query = state.searchQuery.lowercase()
            filtered = filtered.filter { book ->
                book.title.lowercase().contains(query) ||
                        book.author.lowercase().contains(query) ||
                        book.seriesName?.lowercase()?.contains(query) == true ||
                        book.narrator?.lowercase()?.contains(query) == true
            }
        }

        // Sort
        val sorted = when (state.sortMode) {
            SortMode.TITLE -> filtered.sortedBy { it.title }
            SortMode.AUTHOR -> filtered.sortedWith(compareBy({ it.author }, { it.title }))
            SortMode.PROGRESS -> filtered.sortedByDescending { it.progress }
            SortMode.RECENT_PROGRESS -> filtered.sortedWith(
                compareByDescending<AudioBook> { if (it.progress > 0) 1 else 0 }
                    .thenByDescending { it.currentTime.inWholeSeconds }
                    .thenBy { it.title }
            )
            SortMode.DEFAULT -> filtered
        }

        _uiState.update { it.copy(filteredBooks = sorted.toList()) }
    }
}
