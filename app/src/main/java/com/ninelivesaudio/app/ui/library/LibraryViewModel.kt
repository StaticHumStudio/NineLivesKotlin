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
import com.ninelivesaudio.app.service.SettingsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

enum class ViewMode { ALL, SERIES, AUTHOR, GENRE }
enum class SortMode {
    RECENTLY_ADDED,      // Newest first
    TITLE_AZ,            // Title A→Z
    TITLE_ZA,            // Title Z→A
    AUTHOR_AZ,           // Author A→Z
    AUTHOR_ZA,           // Author Z→A
    PROGRESS_HIGH,       // Most progress first
    PROGRESS_LOW,        // Least progress first
    DURATION_LONG,       // Longest books first
    DURATION_SHORT,      // Shortest books first
    RECENTLY_PLAYED,     // Recently played first
    UNPLAYED_FIRST,      // Unplayed books first
}

enum class LibraryTab(val label: String) {
    All("All"),
    InProgress("In Progress"),
    Completed("Completed"),
    Downloaded("Downloaded"),
}

// ─── Grouped section models ───────────────────────────────────────────────

sealed class LibraryListItem {
    data class GroupHeader(
        val groupKey: String,
        val title: String,
        val count: Int,
        val isExpanded: Boolean,
    ) : LibraryListItem()

    data class BookRow(
        val groupKey: String,
        val book: AudioBook,
    ) : LibraryListItem()
}

data class GroupedSection(
    val key: String,
    val title: String,
    val books: List<AudioBook>,
)

private const val UNKNOWN_SERIES_GROUP = "Standalone/Unknown Series"
private const val UNKNOWN_AUTHOR_GROUP = "Unknown Author"
private const val UNKNOWN_GENRE_GROUP = "Uncategorized Genre"

// ─── ViewModel ───────────────────────────────────────────────────────────

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val audioBookRepository: AudioBookRepository,
    private val apiService: ApiService,
    private val connectivityMonitor: ConnectivityMonitor,
    private val settingsManager: SettingsManager,
) : ViewModel() {

    // ─── UI State ─────────────────────────────────────────────────────────

    data class UiState(
        val isLoading: Boolean = false,
        val isRefreshing: Boolean = false,
        val libraries: List<Library> = emptyList(),
        val selectedLibrary: Library? = null,
        val filteredBooks: List<AudioBook> = emptyList(),
        val searchQuery: String = "",
        val viewMode: ViewMode = ViewMode.ALL,
        val sortMode: SortMode = SortMode.RECENTLY_PLAYED,
        val selectedGroupFilter: String? = null,
        val availableGroups: List<String> = emptyList(),
        val groupedSections: List<GroupedSection> = emptyList(),
        val expandedGroups: Set<String> = emptySet(),
        val selectedTab: LibraryTab = LibraryTab.All,
        val hideFinished: Boolean = false,
        val showDownloadedOnly: Boolean = false,
        val connectionStatus: ConnectionStatus = ConnectionStatus.OFFLINE,
        val errorMessage: String? = null,
        val totalBookCount: Int = 0,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /**
     * Epoch counter that increments each time the Library screen is entered.
     * Used as a seed component for whisper selection so whispers re-roll
     * each time the user taps the Library nav button.
     */
    private val _whisperEpoch = MutableStateFlow(0)
    val whisperEpoch: StateFlow<Int> = _whisperEpoch.asStateFlow()

    /** Called by LibraryScreen on each composition entry to re-roll whispers. */
    fun incrementWhisperEpoch() {
        _whisperEpoch.update { it + 1 }
    }

    // Search debounce
    private var searchJob: Job? = null

    // Keep one in-memory source list to avoid duplicating full collections in UiState.
    private var cachedBooks: List<AudioBook> = emptyList()

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

            // Restore persisted library selection, fall back to first available
            val savedId = settingsManager.currentSettings.selectedLibraryId
            val selected = _uiState.value.selectedLibrary
                ?: libs.firstOrNull { it.id == savedId }
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
        try {
            // Load local first
            var books = audioBookRepository.getByLibraryWithLastPlayed(libraryId)

            // Sync from server
            try {
                val serverBooks = audioBookRepository.syncLibraryItems(libraryId)
                if (serverBooks.isNotEmpty()) {
                    books = audioBookRepository.getByLibraryWithLastPlayed(libraryId)
                }
            } catch (_: Exception) {
                // Use cached
            }

            cachedBooks = books
            updateAvailableGroups()
            applyFilter()
        } catch (e: Exception) {
            _uiState.update {
                it.copy(errorMessage = "Failed to load audiobooks: ${e.message}")
            }
        }
    }

    // ─── User Actions ─────────────────────────────────────────────────────

    fun onLibrarySelected(library: Library) {
        _uiState.update {
            it.copy(
                selectedLibrary = library,
                searchQuery = "",
                isLoading = true,
            )
        }
        viewModelScope.launch {
            // Persist selection so the whole app picks it up
            settingsManager.updateSettings { it.copy(selectedLibraryId = library.id) }
            // Full resync for the newly selected library
            loadAudioBooks(library.id)
            _uiState.update { it.copy(isLoading = false) }
        }
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

    fun onLibraryTabChanged(tab: LibraryTab) {
        _uiState.update { it.copy(selectedTab = tab) }
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

    fun onGroupExpansionToggled(groupKey: String) {
        _uiState.update { state ->
            val updated = state.expandedGroups.toMutableSet().apply {
                if (!add(groupKey)) remove(groupKey)
            }
            state.copy(expandedGroups = updated)
        }
    }

    fun resetFilters() {
        _uiState.update {
            it.copy(
                searchQuery = "",
                viewMode = ViewMode.ALL,
                selectedGroupFilter = null,
                selectedTab = LibraryTab.All,
                hideFinished = false,
                showDownloadedOnly = false,
                sortMode = SortMode.RECENTLY_PLAYED,
            )
        }
        updateAvailableGroups()
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
            ViewMode.SERIES -> cachedBooks
                .mapNotNull { it.seriesName }
                .filter { it.isNotEmpty() }
                .distinct()
                .sorted()

            ViewMode.AUTHOR -> cachedBooks
                .map { it.author }
                .filter { it.isNotEmpty() }
                .distinct()
                .sorted()

            ViewMode.GENRE -> cachedBooks
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
        var filtered = cachedBooks.asSequence()

        // Library filter
        val libraryId = state.selectedLibrary?.id
        if (libraryId != null) {
            filtered = filtered.filter { it.libraryId == libraryId }
        }

        // Tab-based filtering
        filtered = when (state.selectedTab) {
            LibraryTab.All -> filtered
            LibraryTab.InProgress -> filtered.filter {
                it.hasProgress && !it.isFinished && it.progressPercent < 99.5
            }
            LibraryTab.Completed -> filtered.filter {
                it.isFinished || it.progress >= 1.0 || it.progressPercent >= 99.5
            }
            LibraryTab.Downloaded -> filtered.filter { it.isDownloaded }
        }

        // Hide finished
        if (state.hideFinished) {
            filtered = filtered.filter { !it.isFinished && it.progress < 1.0 && it.progressPercent < 99.5 }
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

        // Sort and group
        val sortedBooks = sortBooks(filtered.toList(), state.sortMode)
        val groupedSections = buildGroupedSections(
            books = sortedBooks,
            viewMode = state.viewMode,
            sortMode = state.sortMode,
        )

        // Preserve expansions for existing groups; auto-expand newly appearing groups
        val groupKeys = groupedSections.map { it.key }.toSet()
        val previousKeys = state.groupedSections.map { it.key }.toSet()
        val expandedGroups = state.expandedGroups
            .filterTo(mutableSetOf()) { it in groupKeys }
            .apply { addAll(groupKeys - previousKeys) }

        _uiState.update {
            it.copy(
                filteredBooks = sortedBooks,
                groupedSections = groupedSections,
                expandedGroups = expandedGroups,
                totalBookCount = cachedBooks.size,
            )
        }
    }
}

// ─── Grouping helpers (internal for testability) ──────────────────────────

internal fun buildGroupedSections(
    books: List<AudioBook>,
    viewMode: ViewMode,
    sortMode: SortMode,
): List<GroupedSection> {
    if (viewMode == ViewMode.ALL) return emptyList()

    // Genre view uses multi-placement: a book appears in every genre group it belongs to.
    val grouped = mutableMapOf<String, MutableList<AudioBook>>()
    books.forEach { book ->
        val keys = groupingKeysForBook(book, viewMode)
        keys.forEach { key -> grouped.getOrPut(key) { mutableListOf() }.add(book) }
    }

    return grouped.entries
        .map { (key, values) ->
            GroupedSection(key = key, title = key, books = sortBooks(values, sortMode))
        }
        .sortedWith(groupedSectionComparator(sortMode))
}

internal fun flattenGroupedItems(
    groupedSections: List<GroupedSection>,
    expandedGroups: Set<String>,
): List<LibraryListItem> = buildList {
    groupedSections.forEach { section ->
        val expanded = section.key in expandedGroups
        add(
            LibraryListItem.GroupHeader(
                groupKey = section.key,
                title = section.title,
                count = section.books.size,
                isExpanded = expanded,
            )
        )
        if (expanded) {
            section.books.forEach { add(LibraryListItem.BookRow(groupKey = section.key, book = it)) }
        }
    }
}

private fun groupingKeysForBook(book: AudioBook, viewMode: ViewMode): List<String> = when (viewMode) {
    ViewMode.SERIES -> listOf(book.seriesName?.takeIf { it.isNotBlank() } ?: UNKNOWN_SERIES_GROUP)
    ViewMode.AUTHOR -> listOf(book.author.takeIf { it.isNotBlank() } ?: UNKNOWN_AUTHOR_GROUP)
    ViewMode.GENRE -> book.genres
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()
        .ifEmpty { listOf(UNKNOWN_GENRE_GROUP) }
    ViewMode.ALL -> emptyList()
}

private fun groupedSectionComparator(sortMode: SortMode): Comparator<GroupedSection> {
    val alphaAsc = compareBy<GroupedSection> { it.title.lowercase() }
    return when (sortMode) {
        SortMode.TITLE_ZA, SortMode.AUTHOR_ZA -> alphaAsc.reversed()
        SortMode.TITLE_AZ, SortMode.AUTHOR_AZ -> alphaAsc
        SortMode.PROGRESS_LOW, SortMode.DURATION_SHORT ->
            compareBy<GroupedSection> { it.books.firstOrNull()?.let { b -> sortSignal(b, sortMode) } ?: Long.MAX_VALUE }
                .then(alphaAsc)
        else ->
            compareByDescending<GroupedSection> { it.books.firstOrNull()?.let { b -> sortSignal(b, sortMode) } ?: Long.MIN_VALUE }
                .then(alphaAsc)
    }
}

private fun sortSignal(book: AudioBook, sortMode: SortMode): Long = when (sortMode) {
    SortMode.RECENTLY_ADDED -> book.addedAt ?: Long.MIN_VALUE
    SortMode.RECENTLY_PLAYED -> book.lastPlayedAt ?: Long.MIN_VALUE
    SortMode.PROGRESS_HIGH, SortMode.PROGRESS_LOW -> (book.progressPercent * 1000).toLong()
    SortMode.DURATION_LONG, SortMode.DURATION_SHORT -> book.duration.inWholeSeconds
    SortMode.UNPLAYED_FIRST -> if (book.hasProgress) 0L else 1L
    SortMode.TITLE_AZ, SortMode.TITLE_ZA, SortMode.AUTHOR_AZ, SortMode.AUTHOR_ZA -> 0L
}

internal fun sortBooks(books: List<AudioBook>, sortMode: SortMode): List<AudioBook> {
    val sequence = books.asSequence()
    return when (sortMode) {
        SortMode.RECENTLY_ADDED -> sequence.sortedWith(
            compareByDescending<AudioBook> { it.addedAt ?: Long.MIN_VALUE }
                .thenBy { it.title.lowercase() }
        )
        SortMode.TITLE_AZ -> sequence.sortedBy { it.title.lowercase() }
        SortMode.TITLE_ZA -> sequence.sortedByDescending { it.title.lowercase() }
        SortMode.AUTHOR_AZ -> sequence.sortedWith(compareBy({ it.author.lowercase() }, { it.title.lowercase() }))
        SortMode.AUTHOR_ZA -> sequence.sortedWith(compareByDescending<AudioBook> { it.author.lowercase() }.thenByDescending { it.title.lowercase() })
        SortMode.PROGRESS_HIGH -> sequence.sortedByDescending { it.progressPercent }
        SortMode.PROGRESS_LOW -> sequence.sortedBy { it.progressPercent }
        SortMode.DURATION_LONG -> sequence.sortedByDescending { it.duration.inWholeSeconds }
        SortMode.DURATION_SHORT -> sequence.sortedBy { it.duration.inWholeSeconds }
        SortMode.RECENTLY_PLAYED -> sequence.sortedWith(
            // Treat books with no playback history as oldest via Long.MIN_VALUE fallback.
            compareByDescending<AudioBook> { it.lastPlayedAt ?: Long.MIN_VALUE }
                .thenBy { it.title.lowercase() }
        )
        SortMode.UNPLAYED_FIRST -> sequence.sortedWith(
            compareBy<AudioBook> { if (it.hasProgress) 1 else 0 }
                .thenBy { it.title.lowercase() }
        )
    }.toList()
}
