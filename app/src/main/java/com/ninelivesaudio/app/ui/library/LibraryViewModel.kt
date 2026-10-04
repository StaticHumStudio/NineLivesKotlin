package com.ninelivesaudio.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninelivesaudio.app.entitlement.EntitlementRepository
import com.ninelivesaudio.app.entitlement.FreeTier
import com.ninelivesaudio.app.data.local.entity.LocalCatalogEntry
import com.ninelivesaudio.app.data.remote.ApiService
import com.ninelivesaudio.app.data.remote.RemoteResult
import com.ninelivesaudio.app.data.remote.describeFailure
import com.ninelivesaudio.app.data.remote.valueOrEmpty
import com.ninelivesaudio.app.data.repository.AudioBookRepository
import com.ninelivesaudio.app.data.repository.LibraryRepository
import com.ninelivesaudio.app.data.repository.ReconciledServerLibraryList
import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.domain.model.AppSettings
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.LastSyncRecord
import com.ninelivesaudio.app.domain.model.Library
import com.ninelivesaudio.app.domain.model.SyncResult
import com.ninelivesaudio.app.service.ConnectivityMonitor
import com.ninelivesaudio.app.service.ConnectivityMonitor.ConnectionStatus
import com.ninelivesaudio.app.service.PersistedSyncOutcome
import com.ninelivesaudio.app.service.SettingsManager
import com.ninelivesaudio.app.service.buildShelfSyncReport
import com.ninelivesaudio.app.service.lastSyncForCurrentServer
import com.ninelivesaudio.app.service.persistActiveLibrarySelection
import com.ninelivesaudio.app.service.persistSyncOutcome
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

/**
 * A new sync outcome that brought data back (not a plain failure) should
 * re-query the shelf. The first record seen is skipped because the initial
 * load already queried it.
 */
internal fun shouldRequeryShelfAfterSync(
    previousSequence: Long?,
    record: LastSyncRecord?,
): Boolean = previousSequence != null &&
    record != null &&
    record.outcomeSequence != previousSequence &&
    record.result != SyncResult.FAILED

/**
 * Whether a sync that brought data back needs the library list and selection
 * reloaded, not just the shelf re-filtered. Re-filtering with nothing selected
 * is a no-op, so an offline cold start with no saved libraries stayed empty
 * after the server returned. An empty cache with nothing shown stays put, or a
 * server with no libraries would reload on every sync record it writes.
 * Membership is compared as a set: the shown list puts retained libraries
 * after fetched ones while the cache sorts by display order, and treating
 * that order difference as a change reloaded on every sync, forever.
 */
internal fun shouldReloadLibrariesAfterSync(
    selectedLibrary: Library?,
    shownLibraryIds: List<String>,
    cachedLibraryIds: List<String>,
): Boolean = (selectedLibrary == null && cachedLibraryIds.isNotEmpty()) ||
    cachedLibraryIds.toSet() != shownLibraryIds.toSet()

/**
 * Which local folders exist, which books each holds (live or archived), and
 * what the shelf shows for each. Progress stays out, or every position save
 * during playback would re-query the shelf.
 */
internal data class LocalCatalogSnapshot(
    val libraryIds: Set<String>,
    val books: Set<LocalCatalogEntry>,
)

internal enum class LocalCatalogChange { NONE, REFILTER, RELOAD }

/**
 * A folder scan writes its library row and books from Settings, and LOCAL
 * mode has no sync record to announce them, so a Library opened mid-scan
 * stayed empty until the tab was re-entered. The ViewModel's very first
 * snapshot is only a baseline, which is safe because every load waits for
 * it before reading, so anything written after it arrives as a later
 * snapshot. A watch that starts later (a switch into LOCAL mode) has no
 * such guarantee, so its first snapshot is checked like any other. A
 * changed library list (or libraries with nothing selected) reloads exactly
 * as a sync does, and any other change re-filters the shelf.
 */
internal fun decideLocalCatalogChange(
    previous: LocalCatalogSnapshot?,
    current: LocalCatalogSnapshot,
    selectedLibrary: Library?,
    shownLibraryIds: List<String>,
    isLoadBaseline: Boolean,
): LocalCatalogChange = when {
    previous == null && isLoadBaseline -> LocalCatalogChange.NONE
    previous == current -> LocalCatalogChange.NONE
    shouldReloadLibrariesAfterSync(
        selectedLibrary = selectedLibrary,
        shownLibraryIds = shownLibraryIds,
        cachedLibraryIds = current.libraryIds.toList(),
    ) -> LocalCatalogChange.RELOAD
    else -> LocalCatalogChange.REFILTER
}

/**
 * The settings a catalog reload saves for a library the user picked on this
 * screen whose own settings write had not finished. The reload cancels that
 * write along with the rest of the load lane, and would otherwise resolve
 * the older saved choice and switch the shelf back. Only an unfinished pick
 * counts, so a choice saved since (Settings has its own picker) wins.
 * Null when there is no such pick, it is already saved, or it is gone.
 */
internal fun settingsKeepingPendingPick(
    pickedLibraryId: String?,
    libraries: List<Library>,
    settings: AppSettings,
): AppSettings? {
    if (pickedLibraryId == null || pickedLibraryId == settings.activeLibraryId) return null
    val picked = libraries.firstOrNull { it.id == pickedLibraryId } ?: return null
    return when (settings.appMode) {
        AppMode.LOCAL -> settings.copy(selectedLocalLibraryId = picked.id).takeIf { picked.isLocal }
        AppMode.AUDIOBOOKSHELF -> settings.copy(selectedLibraryId = picked.id).takeIf { !picked.isLocal }
    }
}

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

/**
 * What a shelf load was started for. The tabs keep the Library alive while
 * another tab is open, so a return only reloads when this changed underneath
 * it (a server, account, mode, or library switch in Settings).
 */
internal data class ShelfIdentity(
    val appMode: AppMode,
    val serverUrl: String,
    val username: String,
    val activeLibraryId: String?,
)

internal fun AppSettings.shelfIdentity() =
    ShelfIdentity(appMode, serverUrl, username, activeLibraryId)

/**
 * A return to the Library reloads when what it shows no longer matches the
 * settings, or when it has nothing to show because its last fetch failed, so
 * the return doubles as a retry.
 * Nothing loaded yet means the first load is still starting and owns the shelf.
 */
internal fun shouldReloadOnLibraryReturn(
    loadedFor: ShelfIdentity?,
    current: ShelfIdentity,
    lastFetchFailed: Boolean,
): Boolean = loadedFor != null && (loadedFor != current || lastFetchFailed)

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
        val lastSyncSequence: Long? = null,
        val lastSyncFailedLibraryIds: List<String>? = null,
        // The SELECTED library's own most recent direct-fetch outcome, kept
        // separate from lastSyncResult (the whole-account aggregate) so an
        // unrelated library's failure can't classify this one's shelf as
        // failed (issue #14, PR #30 review, finding A). Reset to null on
        // every selection change and set by loadAudioBooks() once its own
        // fresh fetch for the newly selected library completes.
        val selectedLibraryFetchResult: SyncResult? = null,
        val selectedLibraryFetchSequence: Long? = null,
        val selectedLibraryFetchPersisted: Boolean = false,
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

    // The settings the shown shelf was loaded for. See [onScreenEntered].
    private var shelfLoadedFor: ShelfIdentity? = null

    /**
     * Called by LibraryScreen each time it enters composition. A tab return
     * keeps the shelf it already has instead of downloading the library again,
     * and re-reads it from Room so progress played elsewhere and a cache
     * cleared in Settings show up. Room changes outside a sync are not watched.
     */
    fun onScreenEntered() {
        val state = _uiState.value
        // Only a failed library with nothing saved retries on return. A saved
        // shelf, even one filtered to nothing, keeps its books, and the
        // periodic and reconnect syncs recover it.
        val lastFetchFailed = state.totalBookCount == 0 &&
            (state.selectedLibraryFetchResult == SyncResult.FAILED || state.errorMessage != null)
        if (shouldReloadOnLibraryReturn(
                loadedFor = shelfLoadedFor,
                current = settingsManager.currentSettings.shelfIdentity(),
                lastFetchFailed = lastFetchFailed,
            )
        ) {
            // This can replace a refresh in the lane, so it owns that spinner.
            libraryLoadLaunch.launch(viewModelScope) { loadLibrariesOwningRefresh() }
        } else if (shelfLoadedFor != null && !state.isLoading) {
            applyFilter()
        }
    }

    // Search debounce
    private var searchJob: Job? = null
    private val filterPublication = LibraryFilterPublication()

    // Initial load, refresh, and selection all write the same shelf state.
    // Keep them in one lane so an older operation cannot finish last.
    private val libraryLoadLaunch = ExclusiveLaunch()

    // Completed by the catalog watch's first snapshot (null outside LOCAL
    // mode). Loads wait for it, so a scan that writes between a load's reads
    // and the watch's first query cannot hide inside that baseline.
    private val localCatalogBaseline = CompletableDeferred<Unit>()

    // A library picked on this screen whose settings write has not finished.
    private var pendingLibraryPickId: String? = null

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
                if (decision.showDownloadedOnly != currentState.showDownloadedOnly) {
                    filterPublication.invalidate()
                }
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
                .map { settings ->
                    settings.lastSyncForCurrentServer()
                        ?.takeIf { settings.appMode != AppMode.LOCAL }
                }
                .distinctUntilChanged()
                .collect { record ->
                    val previousSequence = _uiState.value.lastSyncSequence
                    _uiState.update {
                        it.copy(
                            lastSyncResult = record?.result,
                            lastSyncSequence = record?.outcomeSequence,
                            lastSyncFailedLibraryIds = record?.failedLibraryIds,
                        )
                    }
                    // A background sync (reconnect or periodic) wrote new rows.
                    // Re-query so the shelf shows them, not just a cleared banner.
                    if (shouldRequeryShelfAfterSync(previousSequence, record)) {
                        val state = _uiState.value
                        val cached = visibleCachedLibraries(
                            settings = settingsManager.currentSettings,
                            cached = libraryRepository.getAudiobookshelf(),
                        )
                        if (shouldReloadLibrariesAfterSync(
                                selectedLibrary = state.selectedLibrary,
                                shownLibraryIds = state.libraries.map { it.id },
                                cachedLibraryIds = cached.map { it.id },
                            )
                        ) {
                            // This can replace a manual refresh in the lane, and the
                            // cancelled refresh never clears its spinner, so this
                            // load owns that cleanup.
                            libraryLoadLaunch.launch(viewModelScope) { loadLibrariesOwningRefresh() }
                        } else {
                            applyFilter()
                        }
                    }
                }
        }

        // LOCAL mode has no sync record, so watch the local catalog itself.
        // A folder scan or rescan running in Settings lands here while the
        // tab is open, not only on the next visit.
        viewModelScope.launch {
            var previous: LocalCatalogSnapshot? = null
            observeLocalCatalogWhileLocal().collect { snapshot ->
                val state = _uiState.value
                val change = snapshot?.let {
                    decideLocalCatalogChange(
                        previous = previous,
                        current = it,
                        selectedLibrary = state.selectedLibrary,
                        shownLibraryIds = state.libraries.map { library -> library.id },
                        isLoadBaseline = !localCatalogBaseline.isCompleted,
                    )
                }
                previous = snapshot
                localCatalogBaseline.complete(Unit)
                when (change) {
                    // Same lane cleanup as the sync reload above.
                    LocalCatalogChange.RELOAD -> libraryLoadLaunch.launch(viewModelScope) {
                        loadLibrariesOwningRefresh(keepPendingPick = true)
                    }
                    LocalCatalogChange.REFILTER -> applyFilter()
                    LocalCatalogChange.NONE, null -> Unit
                }
            }
        }

        // Initial load
        libraryLoadLaunch.launch(viewModelScope) {
            loadLibraries()
        }
    }

    /** The local catalog while in LOCAL mode, and null (no Room watch) otherwise. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeLocalCatalogWhileLocal(): Flow<LocalCatalogSnapshot?> =
        settingsManager.settings
            .map { it.appMode == AppMode.LOCAL }
            .distinctUntilChanged()
            .flatMapLatest { isLocal ->
                if (!isLocal) {
                    flowOf(null)
                } else {
                    combine(
                        libraryRepository.observeLocalLibraries()
                            .map { libraries -> libraries.mapTo(mutableSetOf()) { it.id } },
                        audioBookRepository.observeLocalCatalog().map { it.toSet() },
                        ::LocalCatalogSnapshot,
                    )
                }
            }
            .distinctUntilChanged()

    // ─── Loading ──────────────────────────────────────────────────────────

    private suspend fun loadLibraries(keepPendingPick: Boolean = false) {
        shelfLoadedFor = settingsManager.currentSettings.shelfIdentity()
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        localCatalogBaseline.await()

        try {
            val settings = settingsManager.currentSettings
            val serverUrlAtStart = settings.serverUrl
            val isLocalMode = settings.appMode == AppMode.LOCAL
            var libraryResult: RemoteResult<List<Library>>? = null
            // The item load below reuses this probe so a dead server costs one
            // 5s wait on a cold start, not two back to back.
            var serverReachable: Boolean? = null
            val libs = if (isLocalMode) {
                libraryRepository.getLocalLibraries()
            } else {
                if (shouldSyncOnLibraryLoad(
                        isLocalLibrary = false,
                        isOnline = connectivityMonitor.isOnline.value,
                    ) && connectivityMonitor.checkServerReachable().also { serverReachable = it }
                ) {
                    refreshRemoteLibraryList(
                        readCached = {
                            visibleCachedLibraries(
                                settings = settingsManager.currentSettings,
                                cached = libraryRepository.getAudiobookshelf(),
                            )
                        },
                        fetchRemote = libraryRepository::syncFromServerForLibraryLoad,
                    ).also { libraryResult = it.result }.libraries
                } else {
                    visibleCachedLibraries(
                        settings = settingsManager.currentSettings,
                        cached = libraryRepository.getAudiobookshelf(),
                    )
                }
            }

            val pick = pendingLibraryPickId.takeIf { keepPendingPick }
            if (settingsKeepingPendingPick(pick, libs, settingsManager.currentSettings) != null) {
                settingsManager.updateSettings { latest ->
                    settingsKeepingPendingPick(pick, libs, latest) ?: latest
                }
            }
            // Any pick left here came from a lane this load cancelled. It is
            // saved above or, for other loads, superseded by what resolves next.
            pendingLibraryPickId = null
            val selection = persistActiveLibrarySelection(
                libraries = libs,
                settings = settingsManager.currentSettings,
                updateSettings = settingsManager::updateSettings,
            )
            val selected = selection.library
            // The selection this load settled on, so a return does not
            // mistake its own write for a change made in Settings.
            shelfLoadedFor = settingsManager.currentSettings.shelfIdentity()

            _uiState.update {
                it.withLibrarySelection(
                    libraries = libs,
                    selectedLibrary = selected,
                    isLocalMode = isLocalMode,
                )
            }
            filterPublication.invalidate()

            val itemResult = selected?.let {
                loadAudioBooks(it.id, persistResult = false, serverReachable = serverReachable)
            }
            if (!isLocalMode) {
                buildShelfSyncReport(libraryResult, selected, itemResult)?.let { report ->
                    val selectedLibraryFetchResult = selected?.let {
                        buildShelfSyncReport(libraries = null, selectedLibrary = it, items = itemResult)?.result
                    }
                    persistLastSync(
                        report = report,
                        serverUrlAtStart = serverUrlAtStart,
                        selectedLibraryFetchResult = selectedLibraryFetchResult,
                    )
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
        serverReachable: Boolean? = null,
    ): RemoteResult<List<AudioBook>>? {
        var itemResult: RemoteResult<List<AudioBook>>? = null
        try {
            val serverUrlAtStart = settingsManager.currentSettings.serverUrl
            val selected = _uiState.value.selectedLibrary
            // Show the saved shelf before the network. A full fetch of a big
            // library takes minutes, and the spinner only covers an empty shelf.
            if (selected?.isLocal != true) applyFilter()?.join()
            // Only hit the network when a remote library is selected AND we have
            // connectivity. In airplane mode the old code attempted syncLibraryItems
            // regardless, leaving the switch spinning on a doomed request until the
            // OkHttp timeout. Skipping the sync lets cached data load instantly.
            // A live VPN (Tailscale) keeps isOnline true with the server gone,
            // so the short /ping probe decides before the 30s request does.
            if (shouldSyncOnLibraryLoad(
                    isLocalLibrary = selected?.isLocal == true,
                    isOnline = connectivityMonitor.isOnline.value,
                ) && (serverReachable ?: connectivityMonitor.checkServerReachable())
            ) {
                itemResult = refreshSelectedLibraryItems(
                    libraryId = libraryId,
                    fetchRemote = audioBookRepository::syncLibraryItems,
                )
                // The selected library's own outcome, tracked separately
                // from the whole-account aggregate lastSyncResult (issue
                // #14, PR #30 review, finding A) — see decideLibraryShelf.
                val ownShelfReport = selected?.let {
                    buildShelfSyncReport(libraries = null, selectedLibrary = it, items = itemResult)
                }
                _uiState.update {
                    it.copy(
                        selectedLibraryFetchResult = ownShelfReport?.result,
                        selectedLibraryFetchSequence = null,
                        selectedLibraryFetchPersisted = false,
                    )
                }
                if (persistResult && ownShelfReport != null) {
                    persistLastSync(
                        report = ownShelfReport,
                        serverUrlAtStart = serverUrlAtStart,
                        selectedLibraryFetchResult = ownShelfReport.result,
                    )
                }
            }

            // The load owns the spinner, so it waits for the filtered shelf
            // before its caller clears it. A superseded filter job returns
            // from join at once and the newer one publishes instead.
            applyFilter()?.join()
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
        completedAtMs: Long = System.currentTimeMillis(),
        selectedLibraryFetchResult: SyncResult? = null,
    ): Long? {
        val outcome = persistSyncOutcome(
            report = report,
            completedAtMs = completedAtMs,
            serverUrlAtStart = serverUrlAtStart,
            isEligibleSession = { it.appMode == AppMode.AUDIOBOOKSHELF },
            updateSettingsIfAuthenticated = settingsManager::updateSettingsIfAuthenticated,
        )
        val selectedFetchSequence = selectedLibraryFetchSequence(outcome)
        val selectedFetchPersisted = outcome.recorded && outcome.persisted
        val currentRecord = settingsManager.currentSettings.lastSyncForCurrentServer()
            ?.takeIf { settingsManager.currentSettings.appMode != AppMode.LOCAL }
        _uiState.update {
            val current = it.copy(
                lastSyncResult = currentRecord?.result,
                lastSyncSequence = currentRecord?.outcomeSequence,
                lastSyncFailedLibraryIds = currentRecord?.failedLibraryIds,
            )
            if (selectedLibraryFetchResult != null &&
                current.selectedLibraryFetchResult == selectedLibraryFetchResult
            ) {
                current.copy(
                    selectedLibraryFetchSequence = selectedFetchSequence,
                    selectedLibraryFetchPersisted = selectedFetchPersisted,
                )
            } else {
                current
            }
        }
        return selectedFetchSequence
    }

    // ─── User Actions ─────────────────────────────────────────────────────

    fun onLibrarySelected(library: Library) {
        searchJob?.cancel()
        filterPublication.invalidate()
        _uiState.update {
            it.copy(
                selectedLibrary = library,
                searchQuery = "",
                isLoading = true,
                // A stale outcome from whatever was previously selected must
                // never be read as this library's own verdict.
                selectedLibraryFetchResult = null,
                selectedLibraryFetchSequence = null,
                selectedLibraryFetchPersisted = false,
            )
        }
        pendingLibraryPickId = library.id
        libraryLoadLaunch.launch(viewModelScope) {
            // Persist selection so the whole app picks it up
            settingsManager.updateSettings {
                if (library.isLocal) {
                    it.copy(selectedLocalLibraryId = library.id)
                } else {
                    it.copy(selectedLibraryId = library.id)
                }
            }
            if (pendingLibraryPickId == library.id) pendingLibraryPickId = null
            shelfLoadedFor = settingsManager.currentSettings.shelfIdentity()
            // Full resync for the newly selected library
            loadAudioBooks(library.id)
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun onSearchQueryChanged(query: String) {
        filterPublication.invalidate()
        _uiState.update { it.copy(searchQuery = query) }
        // Debounce search
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)
            applyFilter()
        }
    }

    fun onViewModeChanged(mode: ViewMode) {
        updateFilterInputs { it.copy(viewMode = mode, selectedGroupFilter = null) }
    }

    fun onSortModeChanged(mode: SortMode) {
        updateFilterInputs { it.copy(sortMode = mode) }
    }

    fun onGroupFilterSelected(group: String?) {
        updateFilterInputs { it.copy(selectedGroupFilter = group) }
    }

    fun onLibraryTabChanged(tab: LibraryTab) {
        updateFilterInputs { it.copy(selectedTab = tab) }
    }

    fun onHideFinishedChanged(value: Boolean) {
        updateFilterInputs { it.copy(hideFinished = value) }
    }

    fun onShowDownloadedOnlyChanged(value: Boolean) {
        autoDownloadedOnly = false
        updateFilterInputs { it.copy(showDownloadedOnly = value) }
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
        searchJob?.cancel()
        updateFilterInputs {
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
    }

    fun refresh() {
        libraryLoadLaunch.launch(viewModelScope) {
            _uiState.update { it.copy(isRefreshing = true) }
            loadLibrariesOwningRefresh()
        }
    }

    /** Load, then clear the refresh spinner unless a newer load took over the lane. */
    private suspend fun loadLibrariesOwningRefresh(keepPendingPick: Boolean = false) {
        try {
            loadLibraries(keepPendingPick)
        } finally {
            updateLibraryLoadStateIfActive {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    // ─── Filter/Sort Logic ────────────────────────────────────────────────

    private data class FilterSnapshot(
        val request: LibraryFilterRequest,
        val libraries: List<Library>,
        val selectedTab: LibraryTab,
        val isLocalMode: Boolean,
        val hideFinished: Boolean,
        val showDownloadedOnly: Boolean,
        val searchQuery: String,
        val sortMode: SortMode,
        val viewMode: ViewMode,
        val selectedGroupFilter: String?,
    )

    private data class FilterResult(
        val books: List<AudioBook>,
        val availableGroups: List<String>,
        val groupedSections: List<GroupedSection>,
        val totalBookCount: Int,
    )

    private suspend fun buildFilterResult(snapshot: FilterSnapshot): FilterResult? {
        val libraryId = snapshot.request.libraryId

        val groups = when (snapshot.viewMode) {
            ViewMode.SERIES -> audioBookRepository.getDistinctSeries(libraryId)
            ViewMode.AUTHOR -> audioBookRepository.getDistinctAuthors(libraryId)
            ViewMode.GENRE -> audioBookRepository.getDistinctGenres(libraryId)
            ViewMode.ALL -> emptyList()
        }
        if (!filterPublication.isCurrent(snapshot.request)) return null

        // Push filters to SQL — only load the books that match
        val tab = when (snapshot.selectedTab) {
            LibraryTab.All -> 0
            LibraryTab.InProgress -> 1
            LibraryTab.Completed -> 2
            LibraryTab.Downloaded -> 3
            // Defensive: if the Archive tab is somehow selected outside LOCAL
            // mode (stale state after a mode switch), fall back to All so the
            // shelf is not silently empty.
            LibraryTab.Archive -> if (snapshot.isLocalMode) 4 else 0
        }
        val storedBooks = audioBookRepository.getFilteredBooks(
            libraryId = libraryId,
            tab = tab,
            hideFinished = snapshot.hideFinished,
            downloadedOnly = snapshot.showDownloadedOnly,
            searchQuery = snapshot.searchQuery.trim(),
        )
        if (!filterPublication.isCurrent(snapshot.request)) return null
        val accessibleLocalIds = localFolderAccess.accessibleLibraryIds(snapshot.libraries)
        val books = storedBooks
            .map { reconcileLocalBookAccess(it, accessibleLocalIds).book }
            .filterNot {
                !it.isDownloaded &&
                    (snapshot.selectedTab == LibraryTab.Downloaded || snapshot.showDownloadedOnly)
            }

        // Clamped at the point of consumption, not just in the UI. Gating the
        // chips stops a free user CHOOSING a premium sort, but says nothing
        // about one already selected before a downgrade, which would otherwise
        // keep running behind a greyed control.
        //
        // The stored choice in uiState is left alone, so unlocking restores it.
        val isUnlocked = entitlements.current.isUnlocked
        val effectiveSort = FreeTier.effectiveSort(snapshot.sortMode, isUnlocked)
        val effectiveViewMode = FreeTier.effectiveViewMode(snapshot.viewMode, isUnlocked)

        // Sort and group in-memory (complex logic stays in Kotlin)
        val sortedBooks = sortBooks(books, effectiveSort)
        val groupedSections = buildGroupedSections(
            books = sortedBooks,
            viewMode = effectiveViewMode,
            sortMode = effectiveSort,
        )

        // Get total count from DB (not from filtered set)
        val totalCount = audioBookRepository.countByLibrary(libraryId)
        if (!filterPublication.isCurrent(snapshot.request)) return null

        return FilterResult(
            books = sortedBooks,
            availableGroups = groups,
            groupedSections = groupedSections,
            totalBookCount = totalCount,
        )
    }

    private fun publishFilterResult(result: FilterResult) {
        _uiState.update {
            val groupKeys = result.groupedSections.map { section -> section.key }.toSet()
            val previousKeys = it.groupedSections.map { section -> section.key }.toSet()
            val expandedGroups = it.expandedGroups
                .filterTo(mutableSetOf()) { key -> key in groupKeys }
                .apply { addAll(groupKeys - previousKeys) }
            it.copy(
                filteredBooks = result.books,
                availableGroups = result.availableGroups,
                groupedSections = result.groupedSections,
                expandedGroups = expandedGroups,
                totalBookCount = result.totalBookCount,
            )
        }
    }

    /**
     * Starts the filtered-shelf calculation and returns its job, or null when
     * no library is selected. Non-suspend callers drop the job. A load that
     * owns a loading indicator joins it.
     */
    private fun applyFilter(): Job? {
        val state = _uiState.value
        val libraryId = state.selectedLibrary?.id ?: run {
            filterPublication.invalidate()
            return null
        }
        val snapshot = FilterSnapshot(
            request = filterPublication.replace(libraryId),
            libraries = state.libraries,
            selectedTab = state.selectedTab,
            isLocalMode = state.isLocalMode,
            hideFinished = state.hideFinished,
            showDownloadedOnly = state.showDownloadedOnly,
            searchQuery = state.searchQuery,
            sortMode = state.sortMode,
            viewMode = state.viewMode,
            selectedGroupFilter = state.selectedGroupFilter,
        )
        return filterPublication.launch(
            scope = viewModelScope,
            request = snapshot.request,
            // Decoding and sorting thousands of books stays off the main thread.
            load = { withContext(Dispatchers.Default) { buildFilterResult(snapshot) } },
            onFailure = { e ->
                _uiState.update { it.copy(errorMessage = "Failed to load audiobooks: ${e.message}") }
            },
        ) { result ->
            result?.let(::publishFilterResult)
        }
    }

    private inline fun updateFilterInputs(transform: (UiState) -> UiState) {
        filterPublication.invalidate()
        _uiState.update(transform)
        applyFilter()
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
    fetchRemote: suspend () -> ReconciledServerLibraryList,
): RemoteLibraryRefresh {
    val cached = readCached()
    val remote = try {
        fetchRemote()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ReconciledServerLibraryList(RemoteResult.Failed(describeFailure(e)), null)
    }
    val result = remote.result
    val fetched = result.valueOrEmpty()
    return RemoteLibraryRefresh(
        libraries = when (result) {
            is RemoteResult.Ok -> {
                val reconciled = remote.reconciledLibraries ?: result.value
                val fetchedIds = result.value.mapTo(mutableSetOf()) { it.id }
                result.value + reconciled.filterNot { it.id in fetchedIds }
            }
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
    // A stale outcome from whatever was PREVIOUSLY selected must never be
    // read as this (possibly different) library's own verdict — cleared on
    // every selection pass and re-set once loadAudioBooks() gets a fresh
    // result for the current selection.
    selectedLibraryFetchResult = null,
    selectedLibraryFetchSequence = null,
    selectedLibraryFetchPersisted = false,
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
    // Complete reconciliation removes each library without downloaded books
    // before it records an empty result. Any row left in the cache belongs to
    // a download and must remain selectable while offline.
    return if (serverConfirmedEmpty && cached.isEmpty()) emptyList() else cached
}

internal fun librarySyncResult(settings: AppSettings): SyncResult? =
    settings.lastSyncForCurrentServer()?.result?.takeIf { settings.appMode != AppMode.LOCAL }

/**
 * [lastSyncResult] is ONE aggregate for the whole account: SyncManager's
 * background sync folds every library's item fetch into it, and the first
 * failure wins. Applying that aggregate directly to whichever library the
 * user happens to have open would fail an unrelated, perfectly healthy
 * shelf just because some OTHER library timed out (issue #14, PR #30
 * review, finding A).
 *
 * [selectedLibraryFetchResult] is the SELECTED library's own most recent
 * direct fetch outcome (set by loadAudioBooks() from the exact RemoteResult
 * it already fetches for that library specifically), and takes priority only
 * while it has a newer sequence than the aggregate record. An equal sequence
 * stays authoritative only when the selected result was durably recorded with
 * that aggregate. A later successful aggregate always supersedes an
 * unpersisted direct verdict. A later degraded aggregate supersedes it only
 * when its failure scope includes this library, or is null for a legacy or
 * otherwise unscoped record. The aggregate is still
 * consulted as a fallback when there is no per-library signal yet (a fresh
 * load that never ran its own live fetch, e.g. offline), but only when its
 * scope applies to the selected library.
 */
internal fun decideLibraryShelf(
    lastSyncResult: SyncResult?,
    lastSyncSequence: Long? = null,
    lastSyncFailedLibraryIds: List<String>? = null,
    selectedLibraryFetchResult: SyncResult? = null,
    selectedLibraryFetchSequence: Long? = null,
    selectedLibraryFetchPersisted: Boolean = false,
    selectedLibraryId: String? = null,
    cachedCount: Int,
): LibraryShelfDecision {
    val aggregateIsNewer = selectedLibraryFetchResult != null && lastSyncSequence != null &&
        (selectedLibraryFetchSequence == null ||
            lastSyncSequence > selectedLibraryFetchSequence ||
            (lastSyncSequence == selectedLibraryFetchSequence && !selectedLibraryFetchPersisted))
    val aggregateAppliesToSelectedLibrary = lastSyncResult == SyncResult.SUCCESS ||
        lastSyncFailedLibraryIds == null ||
        selectedLibraryId in lastSyncFailedLibraryIds
    val effectiveResult = if (aggregateIsNewer && aggregateAppliesToSelectedLibrary) {
        lastSyncResult
    } else {
        selectedLibraryFetchResult ?: lastSyncResult?.takeIf { aggregateAppliesToSelectedLibrary }
            ?: SyncResult.SUCCESS
    }
    val degraded = effectiveResult?.takeIf { it != SyncResult.SUCCESS }
    return when {
        cachedCount > 0 -> LibraryShelfDecision.ShowShelf(warning = degraded)
        degraded != null -> LibraryShelfDecision.LoadFailed(result = degraded)
        else -> LibraryShelfDecision.Empty
    }
}

/**
 * The settings transform resolves its candidate record before attempting the
 * encrypted write. Keep that record's sequence for the selected shelf even
 * when storage rejects the write, so the fresh fetch cannot be hidden by an
 * older durable aggregate.
 */
internal fun selectedLibraryFetchSequence(outcome: PersistedSyncOutcome): Long? =
    outcome.record?.outcomeSequence

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
