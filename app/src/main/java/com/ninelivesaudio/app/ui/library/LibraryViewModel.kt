package com.ninelivesaudio.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninelivesaudio.app.entitlement.EntitlementRepository
import com.ninelivesaudio.app.entitlement.FreeTier
import com.ninelivesaudio.app.data.remote.ApiService
import com.ninelivesaudio.app.data.remote.RemoteResult
import com.ninelivesaudio.app.data.remote.describeFailure
import com.ninelivesaudio.app.data.remote.valueOrEmpty
import com.ninelivesaudio.app.data.repository.AudioBookRepository
import com.ninelivesaudio.app.data.repository.LibraryRepository
import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.domain.model.AppSettings
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.Library
import com.ninelivesaudio.app.domain.model.SyncResult
import com.ninelivesaudio.app.service.ConnectivityMonitor
import com.ninelivesaudio.app.service.ConnectivityMonitor.ConnectionStatus
import com.ninelivesaudio.app.service.SettingsManager
import com.ninelivesaudio.app.service.buildShelfSyncReport
import com.ninelivesaudio.app.service.resolveActiveLibrarySelection
import com.ninelivesaudio.app.service.lastSyncForCurrentServer
import com.ninelivesaudio.app.service.withLastSyncIfServerUnchanged
import com.ninelivesaudio.app.service.local.LocalFolderAccess
import com.ninelivesaudio.app.service.local.reconcileLocalBookAccess
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
    Archive("Archive"),
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

internal data class DownloadedOnlyFilterState(
    val showDownloadedOnly: Boolean,
    val autoDownloadedOnly: Boolean,
)

internal fun decideDownloadedOnlyFilter(
    previousStatus: ConnectionStatus,
    newStatus: ConnectionStatus,
    current: DownloadedOnlyFilterState,
): DownloadedOnlyFilterState {
    val connectionWasLost = previousStatus == ConnectionStatus.OFFLINE ||
        previousStatus == ConnectionStatus.SERVER_UNREACHABLE
    val connectionLost = newStatus == ConnectionStatus.OFFLINE ||
        newStatus == ConnectionStatus.SERVER_UNREACHABLE
    return when {
        !connectionWasLost &&
            connectionLost &&
            !current.showDownloadedOnly -> current.copy(
                showDownloadedOnly = true,
                autoDownloadedOnly = true,
            )
        newStatus == ConnectionStatus.CONNECTED && current.autoDownloadedOnly ->
            DownloadedOnlyFilterState(
                showDownloadedOnly = false,
                autoDownloadedOnly = false,
            )
        else -> current
    }
}

/**
 * Cancels whatever job the previous [launch] call started before starting a
 * new one, so an older, still-running call can never outlive a newer one and
 * overwrite its persisted or displayed result.
 *
 * A single [Job] reference rather than a generation counter: cancelling the
 * stale coroutine outright stops ANY uiState write or persist call it was
 * mid-way through, not just a final one gated on a generation check. Callers
 * whose body can be interrupted mid-network-call must let
 * [kotlinx.coroutines.CancellationException] escape their own try/catch
 * (see [rethrowLibraryLoadCancellation]) or the cancellation would still be
 * reported as an ordinary failure.
 */
internal class ExclusiveLaunch {
    private var job: Job? = null

    fun launch(scope: CoroutineScope, block: suspend CoroutineScope.() -> Unit): Job {
        job?.cancel()
        return scope.launch(block = block).also { job = it }
    }
}

internal suspend fun updateLibraryLoadStateIfActive(update: () -> Unit) {
    if (currentCoroutineContext().isActive) update()
}

// ─── ViewModel ───────────────────────────────────────────────────────────

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val audioBookRepository: AudioBookRepository,
    private val apiService: ApiService,
    private val connectivityMonitor: ConnectivityMonitor,
    private val settingsManager: SettingsManager,
    private val entitlements: EntitlementRepository,
    private val localFolderAccess: LocalFolderAccess,
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
        val isLocalMode: Boolean = false, // LOCAL mode shows the Archive tab
        val hideFinished: Boolean = false,
        val showDownloadedOnly: Boolean = false,
        val connectionStatus: ConnectionStatus = ConnectionStatus.OFFLINE,
        val errorMessage: String? = null,
        val totalBookCount: Int = 0,
        val lastSyncResult: SyncResult? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    private var autoDownloadedOnly = false

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

    // Initial load, refresh, and selection all write the same shelf state.
    // Keep them in one lane so an older operation cannot finish last.
    private val libraryLoadLaunch = ExclusiveLaunch()

    init {
        // Observe connectivity and auto-filter to downloaded when offline
        viewModelScope.launch {
            connectivityMonitor.connectionStatus.collect { status ->
                val currentState = _uiState.value
                val decision = decideDownloadedOnlyFilter(
                    previousStatus = currentState.connectionStatus,
                    newStatus = status,
                    current = DownloadedOnlyFilterState(
                        showDownloadedOnly = currentState.showDownloadedOnly,
                        autoDownloadedOnly = autoDownloadedOnly,
                    ),
                )
                autoDownloadedOnly = decision.autoDownloadedOnly
                _uiState.update {
                    it.copy(
                        connectionStatus = status,
                        showDownloadedOnly = decision.showDownloadedOnly,
                    )
                }
                if (decision.showDownloadedOnly != currentState.showDownloadedOnly) applyFilter()
            }
        }

        // Keep the screen tied to the durable result across process restarts
        // and background syncs.
        viewModelScope.launch {
            settingsManager.settings
                .map(::librarySyncResult)
                .distinctUntilChanged()
                .collect { result ->
                    _uiState.update { it.copy(lastSyncResult = result) }
                }
        }

        // Initial load
        libraryLoadLaunch.launch(viewModelScope) {
            loadLibraries()
        }
    }

    // ─── Loading ──────────────────────────────────────────────────────────

    private suspend fun loadLibraries() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }

        try {
            val settings = settingsManager.currentSettings
            val serverUrlAtStart = settings.serverUrl
            val isLocalMode = settings.appMode == AppMode.LOCAL
            var libraryResult: RemoteResult<List<Library>>? = null
            val libs = if (isLocalMode) {
                libraryRepository.getLocalLibraries()
            } else {
                if (shouldSyncOnLibraryLoad(
                        isLocalLibrary = false,
                        isOnline = connectivityMonitor.isOnline.value,
                    )
                ) {
                    refreshRemoteLibraryList(
                        readCached = {
                            visibleCachedLibraries(
                                settings = settingsManager.currentSettings,
                                cached = libraryRepository.getAudiobookshelf(),
                            )
                        },
                        fetchRemote = libraryRepository::syncFromServer,
                    ).also { libraryResult = it.result }.libraries
                } else {
                    visibleCachedLibraries(
                        settings = settingsManager.currentSettings,
                        cached = libraryRepository.getAudiobookshelf(),
                    )
                }
            }

            val selection = resolveActiveLibrarySelection(libs, settingsManager.currentSettings)
            if (selection.requiresPersistence) {
                settingsManager.saveSettings(selection.settings)
            }
            val selected = selection.library

            _uiState.update {
                it.withLibrarySelection(
                    libraries = libs,
                    selectedLibrary = selected,
                    isLocalMode = isLocalMode,
                )
            }

            val itemResult = selected?.let {
                loadAudioBooks(it.id, persistResult = false)
            }
            if (!isLocalMode) {
                buildShelfSyncReport(libraryResult, selected, itemResult)?.let { report ->
                    persistLastSync(report, serverUrlAtStart)
                }
            }
        } catch (e: Exception) {
            rethrowLibraryLoadCancellation(e)
            _uiState.update {
                it.copy(errorMessage = "Failed to load libraries: ${e.message}")
            }
        } finally {
            updateLibraryLoadStateIfActive {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    private suspend fun loadAudioBooks(
        libraryId: String,
        persistResult: Boolean = true,
    ): RemoteResult<List<AudioBook>>? {
        var itemResult: RemoteResult<List<AudioBook>>? = null
        try {
            val serverUrlAtStart = settingsManager.currentSettings.serverUrl
            val selected = _uiState.value.selectedLibrary
            // Only hit the network when a remote library is selected AND we have
            // connectivity. In airplane mode the old code attempted syncLibraryItems
            // regardless, leaving the switch spinning on a doomed request until the
            // OkHttp timeout. Skipping the sync lets cached data load instantly.
            if (shouldSyncOnLibraryLoad(
                    isLocalLibrary = selected?.isLocal == true,
                    isOnline = connectivityMonitor.isOnline.value,
                )
            ) {
                itemResult = refreshSelectedLibraryItems(
                    libraryId = libraryId,
                    fetchRemote = audioBookRepository::syncLibraryItems,
                )
                if (persistResult && selected != null) {
                    buildShelfSyncReport(
                        libraries = null,
                        selectedLibrary = selected,
                        items = itemResult,
                    )?.let { report -> persistLastSync(report, serverUrlAtStart) }
                }
            }

            updateAvailableGroups(libraryId)
            applyFilterSuspend()
        } catch (e: Exception) {
            rethrowLibraryLoadCancellation(e)
            _uiState.update {
                it.copy(errorMessage = "Failed to load audiobooks: ${e.message}")
            }
        }
        return itemResult
    }

    private suspend fun persistLastSync(
        report: com.ninelivesaudio.app.service.SyncReport,
        serverUrlAtStart: String,
    ) {
        settingsManager.updateSettings {
            it.withLastSyncIfServerUnchanged(
                report = report,
                completedAtMs = System.currentTimeMillis(),
                serverUrlAtStart = serverUrlAtStart,
            )
        }
        _uiState.update {
            it.copy(lastSyncResult = librarySyncResult(settingsManager.currentSettings))
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
        libraryLoadLaunch.launch(viewModelScope) {
            // Persist selection so the whole app picks it up
            settingsManager.updateSettings {
                if (library.isLocal) {
                    it.copy(selectedLocalLibraryId = library.id)
                } else {
                    it.copy(selectedLibraryId = library.id)
                }
            }
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
            applyFilterSuspend()
        }
    }

    fun onViewModeChanged(mode: ViewMode) {
        _uiState.update { it.copy(viewMode = mode, selectedGroupFilter = null) }
        viewModelScope.launch { updateAvailableGroups() }
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
        autoDownloadedOnly = false
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
        autoDownloadedOnly = false
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
        viewModelScope.launch { updateAvailableGroups() }
        applyFilter()
    }

    fun refresh() {
        libraryLoadLaunch.launch(viewModelScope) {
            _uiState.update { it.copy(isRefreshing = true) }
            try {
                loadLibraries()
            } finally {
                updateLibraryLoadStateIfActive {
                    _uiState.update { it.copy(isRefreshing = false) }
                }
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    // ─── Filter/Sort Logic ────────────────────────────────────────────────

    private suspend fun updateAvailableGroups(libraryId: String? = null) {
        val libId = libraryId ?: _uiState.value.selectedLibrary?.id ?: return
        val state = _uiState.value
        val groups = when (state.viewMode) {
            ViewMode.SERIES -> audioBookRepository.getDistinctSeries(libId)
            ViewMode.AUTHOR -> audioBookRepository.getDistinctAuthors(libId)
            ViewMode.GENRE -> audioBookRepository.getDistinctGenres(libId)
            ViewMode.ALL -> emptyList()
        }
        _uiState.update { it.copy(availableGroups = groups) }
    }

    private suspend fun applyFilterSuspend() {
        val state = _uiState.value
        val libraryId = state.selectedLibrary?.id ?: return

        // Push filters to SQL — only load the books that match
        val tab = when (state.selectedTab) {
            LibraryTab.All -> 0
            LibraryTab.InProgress -> 1
            LibraryTab.Completed -> 2
            LibraryTab.Downloaded -> 3
            // Defensive: if the Archive tab is somehow selected outside LOCAL
            // mode (stale state after a mode switch), fall back to All so the
            // shelf is not silently empty.
            LibraryTab.Archive -> if (state.isLocalMode) 4 else 0
        }
        val storedBooks = audioBookRepository.getFilteredBooks(
            libraryId = libraryId,
            tab = tab,
            hideFinished = state.hideFinished,
            downloadedOnly = state.showDownloadedOnly,
            searchQuery = state.searchQuery.trim(),
        )
        val accessibleLocalIds = localFolderAccess.accessibleLibraryIds(state.libraries)
        val books = storedBooks
            .map { reconcileLocalBookAccess(it, accessibleLocalIds).book }
            .filterNot {
                !it.isDownloaded &&
                    (state.selectedTab == LibraryTab.Downloaded || state.showDownloadedOnly)
            }

        // Clamped at the point of consumption, not just in the UI. Gating the
        // chips stops a free user CHOOSING a premium sort, but says nothing
        // about one already selected before a downgrade, which would otherwise
        // keep running behind a greyed control.
        //
        // The stored choice in uiState is left alone, so unlocking restores it.
        val isUnlocked = entitlements.current.isUnlocked
        val effectiveSort = FreeTier.effectiveSort(state.sortMode, isUnlocked)
        val effectiveViewMode = FreeTier.effectiveViewMode(state.viewMode, isUnlocked)

        // Sort and group in-memory (complex logic stays in Kotlin)
        val sortedBooks = sortBooks(books, effectiveSort)
        val groupedSections = buildGroupedSections(
            books = sortedBooks,
            viewMode = effectiveViewMode,
            sortMode = effectiveSort,
        )

        // Preserve expansions for existing groups; auto-expand newly appearing groups
        val groupKeys = groupedSections.map { it.key }.toSet()
        val previousKeys = state.groupedSections.map { it.key }.toSet()
        val expandedGroups = state.expandedGroups
            .filterTo(mutableSetOf()) { it in groupKeys }
            .apply { addAll(groupKeys - previousKeys) }

        // Get total count from DB (not from filtered set)
        val totalCount = audioBookRepository.countByLibrary(libraryId)

        _uiState.update {
            it.copy(
                filteredBooks = sortedBooks,
                groupedSections = groupedSections,
                expandedGroups = expandedGroups,
                totalBookCount = totalCount,
            )
        }
    }

    /** Fire-and-forget filter for non-suspend callers. */
    private fun applyFilter() {
        viewModelScope.launch { applyFilterSuspend() }
    }

}

// ─── Load-path decisions (internal for testability) ───────────────────────

internal sealed interface LibraryShelfDecision {
    data object Empty : LibraryShelfDecision
    data class LoadFailed(val result: SyncResult) : LibraryShelfDecision
    data class ShowShelf(val warning: SyncResult?) : LibraryShelfDecision
}

internal data class RemoteLibraryRefresh(
    val libraries: List<Library>,
    val result: RemoteResult<List<Library>>,
)

internal suspend fun refreshRemoteLibraryList(
    readCached: suspend () -> List<Library>,
    fetchRemote: suspend () -> RemoteResult<List<Library>>,
): RemoteLibraryRefresh {
    val cached = readCached()
    val result = try {
        fetchRemote()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        RemoteResult.Failed(describeFailure(e))
    }
    val fetched = result.valueOrEmpty()
    return RemoteLibraryRefresh(
        libraries = when (result) {
            is RemoteResult.Ok -> result.value
            is RemoteResult.Partial -> fetched.ifEmpty { cached }
            is RemoteResult.Failed -> cached
        },
        result = result,
    )
}

internal suspend fun refreshSelectedLibraryItems(
    libraryId: String,
    fetchRemote: suspend (String) -> RemoteResult<List<AudioBook>>,
): RemoteResult<List<AudioBook>> = try {
    fetchRemote(libraryId)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    RemoteResult.Failed(describeFailure(e))
}

internal fun rethrowLibraryLoadCancellation(error: Exception) {
    if (error is CancellationException) throw error
}

internal fun LibraryViewModel.UiState.withLibrarySelection(
    libraries: List<Library>,
    selectedLibrary: Library?,
    isLocalMode: Boolean,
): LibraryViewModel.UiState = copy(
    libraries = libraries,
    selectedLibrary = selectedLibrary,
    isLocalMode = isLocalMode,
    filteredBooks = if (selectedLibrary == null) emptyList() else filteredBooks,
    availableGroups = if (selectedLibrary == null) emptyList() else availableGroups,
    groupedSections = if (selectedLibrary == null) emptyList() else groupedSections,
    expandedGroups = if (selectedLibrary == null) emptySet() else expandedGroups,
    totalBookCount = if (selectedLibrary == null) 0 else totalBookCount,
)

/**
 * The persisted sync record, but only if it was produced against the server
 * currently configured. A record left over from a server the user has since
 * switched away from is not a verdict about this server's shelf. A switch to
 * a new server (or back to LOCAL and back) must not have that other
 * server's failure or "confirmed empty" render here.
 */
internal fun visibleCachedLibraries(
    settings: AppSettings,
    cached: List<Library>,
): List<Library> {
    val lastSync = settings.lastSyncForCurrentServer()
    val serverConfirmedEmpty = settings.appMode == AppMode.AUDIOBOOKSHELF &&
        lastSync?.result == SyncResult.SUCCESS &&
        lastSync.libraryCount == 0
    return if (serverConfirmedEmpty) emptyList() else cached
}

internal fun librarySyncResult(settings: AppSettings): SyncResult? =
    settings.lastSyncForCurrentServer()?.result?.takeIf { settings.appMode != AppMode.LOCAL }

internal fun decideLibraryShelf(
    lastSyncResult: SyncResult?,
    cachedCount: Int,
): LibraryShelfDecision {
    val degraded = lastSyncResult?.takeIf { it != SyncResult.SUCCESS }
    return when {
        cachedCount > 0 -> LibraryShelfDecision.ShowShelf(warning = degraded)
        degraded != null -> LibraryShelfDecision.LoadFailed(result = degraded)
        else -> LibraryShelfDecision.Empty
    }
}

/**
 * Whether loading a library should attempt a remote sync. Local libraries never
 * sync, and a remote library only syncs when there is connectivity. Offline
 * (airplane mode) the switch must fall through to cached data rather than block
 * on a network request that cannot succeed.
 */
internal fun shouldSyncOnLibraryLoad(isLocalLibrary: Boolean, isOnline: Boolean): Boolean =
    !isLocalLibrary && isOnline

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
