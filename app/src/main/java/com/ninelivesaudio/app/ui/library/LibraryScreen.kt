package com.ninelivesaudio.app.ui.library

// Force rebuild v2
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.ui.components.ContainmentFrame
import com.ninelivesaudio.app.ui.components.ContainmentProgressRing
import com.ninelivesaudio.app.ui.components.CornerSigils
import com.ninelivesaudio.app.ui.components.RingStyle
import com.ninelivesaudio.app.ui.components.StatusPill
import com.ninelivesaudio.app.ui.animation.unhinged.anomalies.AnomalyHost
import com.ninelivesaudio.app.ui.animation.unhinged.anomalies.AnomalyTriggerContext
import com.ninelivesaudio.app.ui.copy.unhinged.CopyEngine
import com.ninelivesaudio.app.ui.copy.unhinged.CopyStyleGuide
import com.ninelivesaudio.app.ui.theme.unhinged.*
import kotlin.random.Random

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onNavigateToBookDetail: (String) -> Unit = {},
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    AnomalyHost(
        currentContext = AnomalyTriggerContext.LIBRARY,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(ArchiveVoidDeep)
        ) {
            // ─── Header ───────────────────────────────────────────────────
            ArchiveHeader(uiState)

            // ─── Search Bar ───────────────────────────────────────────────
            RelicSearchBar(
                query = uiState.searchQuery,
                onQueryChange = viewModel::onSearchQueryChanged,
            )

            // ─── Stone Tabs ─────────────────────────────────────────────
            StoneTabsRow(
                selectedTab = uiState.selectedTab,
                onTabSelected = viewModel::onLibraryTabChanged,
            )

            // ─── Filters + Sorting ─────────────────────────────────────
            LibraryFiltersRow(
                uiState = uiState,
                onViewModeChanged = viewModel::onViewModeChanged,
                onSortModeChanged = viewModel::onSortModeChanged,
                onHideFinishedChanged = viewModel::onHideFinishedChanged,
                onShowDownloadedOnlyChanged = viewModel::onShowDownloadedOnlyChanged,
                onGroupFilterSelected = viewModel::onGroupFilterSelected,
            )

            // ─── Content ──────────────────────────────────────────────────
            PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    uiState.isLoading && uiState.filteredBooks.isEmpty() -> {
                        LoadingState()
                    }
                    uiState.filteredBooks.isEmpty() -> {
                        EmptyState(uiState)
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 12.dp,
                                end = 12.dp,
                                top = 8.dp,
                                bottom = 100.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            itemsIndexed(
                                items = uiState.filteredBooks,
                                key = { _, book -> book.id },
                            ) { index, book ->
                                ArchiveBookListItem(
                                    book = book,
                                    index = index,
                                    onClick = { onNavigateToBookDetail(book.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── Archive Header ──────────────────────────────────────────────────────

@Composable
private fun ArchiveHeader(uiState: LibraryViewModel.UiState) {
    val subtitle = CopyEngine.getSubtitle(
        CopyStyleGuide.Library.LIBRARY_NAV_RITUAL,
        CopyStyleGuide.Library.LIBRARY_NAV_UNHINGED,
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ArchiveVoidDeep)
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "The Archive",
                style = MaterialTheme.typography.headlineMedium,
                color = ArchiveTextPrimary,
                fontWeight = FontWeight.Bold,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = ArchiveTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        StatusPill(connectionStatus = uiState.connectionStatus)
    }
}

// ─── Relic Search Bar ────────────────────────────────────────────────────

@Composable
private fun RelicSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = ArchiveVoidSurface,
        border = BorderStroke(1.dp, ArchiveOutline),
        shadowElevation = 2.dp,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            // Keep it truly single-line height (no chunky default paddings)
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 46.dp),
            placeholder = {
                Text(
                    CopyEngine.getSearchHint(
                        CopyStyleGuide.Search.SEARCH_HINT_NORMAL,
                        CopyStyleGuide.Search.SEARCH_HINT_RITUAL,
                        CopyStyleGuide.Search.SEARCH_HINT_UNHINGED,
                    ),
                    color = ArchiveTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingIcon = {
                Icon(
                    Icons.Outlined.Search,
                    contentDescription = null,
                    tint = ArchiveTextSecondary,
                    modifier = Modifier.size(20.dp),
                )
            },
            trailingIcon = if (query.isNotEmpty()) {
                {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            Icons.Outlined.Clear,
                            contentDescription = "Clear search",
                            tint = ArchiveTextMuted,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            } else null,
            singleLine = true,
            minLines = 1,
            maxLines = 1,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = GoldFilament,
                unfocusedBorderColor = ArchiveOutline.copy(alpha = 0f),
                focusedContainerColor = ArchiveVoidSurface,
                unfocusedContainerColor = ArchiveVoidSurface,
                focusedTextColor = ArchiveTextPrimary,
                unfocusedTextColor = ArchiveTextPrimary,
                cursorColor = GoldFilament,
            ),
        )
    }
}

// ─── Stone Tabs Row ──────────────────────────────────────────────────────

@Composable
private fun StoneTabsRow(
    selectedTab: LibraryTab,
    onTabSelected: (LibraryTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LibraryTab.entries.forEach { tab ->
            val isSelected = tab == selectedTab
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onTabSelected(tab) },
                shape = RoundedCornerShape(10.dp),
                color = if (isSelected) GoldFilamentFaint else ArchiveVoidSurface,
                border = BorderStroke(
                    width = 1.dp,
                    color = if (isSelected) GoldFilament.copy(alpha = 0.6f) else ArchiveOutline,
                ),
            ) {
                Text(
                    text = tab.label,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) GoldFilament else ArchiveTextSecondary,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

// ─── Filters / Sorting (More knobs, less suffering) ───────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryFiltersRow(
    uiState: LibraryViewModel.UiState,
    onViewModeChanged: (ViewMode) -> Unit,
    onSortModeChanged: (SortMode) -> Unit,
    onHideFinishedChanged: (Boolean) -> Unit,
    onShowDownloadedOnlyChanged: (Boolean) -> Unit,
    onGroupFilterSelected: (String?) -> Unit,
) {
    var sortExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Row 1: View mode chips + sort, horizontally scrollable to avoid crowding
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = uiState.viewMode == ViewMode.ALL,
                onClick = { onViewModeChanged(ViewMode.ALL) },
                label = { Text("All") },
            )
            FilterChip(
                selected = uiState.viewMode == ViewMode.SERIES,
                onClick = { onViewModeChanged(ViewMode.SERIES) },
                label = { Text("Series") },
            )
            FilterChip(
                selected = uiState.viewMode == ViewMode.AUTHOR,
                onClick = { onViewModeChanged(ViewMode.AUTHOR) },
                label = { Text("Author") },
            )
            FilterChip(
                selected = uiState.viewMode == ViewMode.GENRE,
                onClick = { onViewModeChanged(ViewMode.GENRE) },
                label = { Text("Genre") },
            )

            Box {
                AssistChip(
                    onClick = { sortExpanded = true },
                    label = {
                        Text(
                            when (uiState.sortMode) {
                                SortMode.RECENTLY_ADDED -> "Newest"
                                SortMode.TITLE_AZ -> "Title A→Z"
                                SortMode.TITLE_ZA -> "Title Z→A"
                                SortMode.AUTHOR_AZ -> "Author A→Z"
                                SortMode.AUTHOR_ZA -> "Author Z→A"
                                SortMode.PROGRESS_HIGH -> "Progress ↑"
                                SortMode.PROGRESS_LOW -> "Progress ↓"
                                SortMode.DURATION_LONG -> "Longest"
                                SortMode.DURATION_SHORT -> "Shortest"
                                SortMode.RECENTLY_PLAYED -> "Recent play"
                                SortMode.UNPLAYED_FIRST -> "Unplayed"
                            }
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Sort,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )

                DropdownMenu(
                    expanded = sortExpanded,
                    onDismissRequest = { sortExpanded = false },
                    containerColor = ArchiveVoidSurface,
                ) {
                    SortMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = mode.name
                                        .lowercase()
                                        .replace('_', ' ')
                                        .replaceFirstChar { it.uppercase() },
                                    color = if (mode == uiState.sortMode) GoldFilament else ArchiveTextPrimary,
                                    fontWeight = if (mode == uiState.sortMode) FontWeight.Bold else FontWeight.Normal,
                                )
                            },
                            onClick = {
                                onSortModeChanged(mode)
                                sortExpanded = false
                            },
                        )
                    }
                }
            }
        }

        // Row 2: Compact toggles
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = uiState.hideFinished,
                onClick = { onHideFinishedChanged(!uiState.hideFinished) },
                label = { Text("Hide finished") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.VisibilityOff,
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                },
            )

            FilterChip(
                selected = uiState.showDownloadedOnly,
                onClick = { onShowDownloadedOnlyChanged(!uiState.showDownloadedOnly) },
                label = { Text("Downloaded") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.DownloadDone,
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                },
            )
        }

        // Row 3: Group picker (visible when a view mode like Series/Author/Genre is active)
        if (uiState.viewMode != ViewMode.ALL && uiState.availableGroups.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // "All" chip to clear the group filter
                FilterChip(
                    selected = uiState.selectedGroupFilter == null,
                    onClick = { onGroupFilterSelected(null) },
                    label = { Text("All") },
                )
                uiState.availableGroups.forEach { group ->
                    FilterChip(
                        selected = uiState.selectedGroupFilter == group,
                        onClick = { onGroupFilterSelected(group) },
                        label = {
                            Text(
                                text = group,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }
    }
}

// ─── Archive Book List Item ──────────────────────────────────────────────

@Composable
private fun ArchiveBookListItem(
    book: AudioBook,
    index: Int,
    onClick: () -> Unit,
) {
    val progress = book.progress.toFloat().coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 800),
        label = "lib_progress_$index",
    )

    val comment = remember(book.id) {
        getBookComment(book)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = ArchiveVoidSurface,
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Cover art with progress ring
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                // Cover image
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .padding(4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(ArchiveVoidElevated),
                ) {
                    if (!book.coverPath.isNullOrEmpty()) {
                        AsyncImage(
                            model = book.coverPath,
                            contentDescription = book.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }

                // Containment Halo progress ring
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .shadow(
                            elevation = 4.dp,
                            shape = RoundedCornerShape(8.dp),
                            clip = false
                        )
                ) {
                    ContainmentProgressRing(
                        progress = animatedProgress,
                        modifier = Modifier.matchParentSize(),
                        style = RingStyle.LibrarySmall,
                        progressColor = GoldFilament,
                        trackColor = ArchiveOutline,
                    )
                }

                // Corner sigils
                CornerSigils(
                    downloaded = book.isDownloaded,
                    bookmarked = false,
                    modifier = Modifier.matchParentSize(),
                )
            }

            // Book info
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Title
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ArchiveTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 18.sp,
                )

                // Author
                Text(
                    text = book.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = ArchiveTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 12.sp,
                )

                // Random comment
                if (comment != null) {
                    Text(
                        text = comment,
                        style = MaterialTheme.typography.labelSmall,
                        color = ArchiveTextMuted.copy(alpha = 0.8f),
                        fontWeight = FontWeight.Light,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }

                // Progress info
                if (book.hasProgress) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${book.progressPercent.toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = GoldFilament,
                            fontWeight = FontWeight.Medium,
                            fontSize = 11.sp,
                        )
                        if (book.chapters.isNotEmpty()) {
                            val chapterIdx = book.getCurrentChapterIndex()
                            if (chapterIdx >= 0) {
                                Text(
                                    text = "Ch ${chapterIdx + 1}/${book.chapters.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ArchiveTextMuted,
                                    fontSize = 10.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── Book Comment Generator ──────────────────────────────────────────────

private fun getBookComment(book: AudioBook): String? {
    val comments = listOf(
        "A timeless classic",
        "Highly recommended",
        "Gripping from start to finish",
        "Beautifully narrated",
        "A must-listen",
        "Captivating storytelling",
        "Thought-provoking",
        "Couldn't stop listening",
        "Powerful and moving",
        "Masterfully crafted",
        "An unforgettable journey",
        "Brilliantly performed",
        "Rich in detail",
        "Engaging and immersive",
        "A true gem",
        "Expertly written",
        "Absolutely riveting",
        "Hauntingly beautiful",
        "Wonderfully complex",
        "A page-turner in audio form",
        null, // 5% chance of no comment
    )
    
    // Use book ID for deterministic randomness
    val seed = book.id.hashCode().toLong()
    val random = Random(seed)
    
    return comments[random.nextInt(comments.size)]
}

// ─── Archive Book Tile (kept for reference/future use) ───────────────────

@Composable
private fun ArchiveBookTile(
    book: AudioBook,
    index: Int,
    onClick: () -> Unit,
) {
    val progress = book.progress.toFloat().coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 800),
        label = "lib_progress_$index",
    )

    // Deterministic misalignment: tile at index 3 gets offset
    val misalignOffset = if (index == 3) IntOffset(2, -3) else IntOffset.Zero

    Column(
        modifier = Modifier
            .offset { misalignOffset }
            .clickable(onClick = onClick),
    ) {
        // Cover art box
        Box(
            modifier = Modifier
                .aspectRatio(1f)
                .clip(RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            // Cover image
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ArchiveVoidElevated),
            ) {
                if (!book.coverPath.isNullOrEmpty()) {
                    AsyncImage(
                        model = book.coverPath,
                        contentDescription = book.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }

            // Containment frame
            ContainmentFrame(
                modifier = Modifier.matchParentSize(),
                inset = 2.dp,
                cornerRadius = 14.dp,
            )

            // Containment Halo progress ring
            ContainmentProgressRing(
                progress = animatedProgress,
                modifier = Modifier.matchParentSize(),
                style = RingStyle.LibrarySmall,
                progressColor = GoldFilament,
                trackColor = ArchiveOutline,
            )

            // Corner sigils
            CornerSigils(
                downloaded = book.isDownloaded,
                bookmarked = false,
                modifier = Modifier.matchParentSize(),
            )
        }

        // Title + Author below the tile
        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = book.title,
            style = MaterialTheme.typography.bodySmall,
            color = ArchiveTextPrimary,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 16.sp,
        )

        Text(
            text = book.author,
            style = MaterialTheme.typography.labelSmall,
            color = ArchiveTextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 11.sp,
        )
    }
}

// ─── Loading State ────────────────────────────────────────────────────────

@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            color = GoldFilament,
            strokeWidth = 2.dp,
        )
    }
}

// ─── Empty State ──────────────────────────────────────────────────────────

@Composable
private fun EmptyState(uiState: LibraryViewModel.UiState) {
    val emptyFlavor = CopyEngine.getEmptyStateFlavor(
        CopyStyleGuide.EmptyStates.EMPTY_LIBRARY_RITUAL,
        CopyStyleGuide.EmptyStates.EMPTY_LIBRARY_UNHINGED,
    )
    val searchFlavor = CopyEngine.getEmptyStateFlavor(
        CopyStyleGuide.EmptyStates.EMPTY_SEARCH_RITUAL,
        CopyStyleGuide.EmptyStates.EMPTY_SEARCH_UNHINGED,
    )

    val (title, subtitle) = when {
        uiState.searchQuery.isNotBlank() ->
            CopyStyleGuide.Search.NO_RESULTS to (searchFlavor ?: "Nothing in the Archive matches \"${uiState.searchQuery}\".")
        uiState.connectionStatus == com.ninelivesaudio.app.service.ConnectivityMonitor.ConnectionStatus.OFFLINE ->
            "The Archive Is Sealed" to (CopyEngine.getEmptyStateFlavor(
                "Connection lost. Check your network.",
                CopyStyleGuide.Errors.CONNECTION_ERROR_UNHINGED,
            ) ?: "Connect to your AudioBookshelf server in Settings.")
        uiState.totalBookCount == 0 ->
            "The Archive Stands Empty" to (emptyFlavor ?: "No artifacts have been catalogued yet.")
        else ->
            "No Relics Match" to "Try adjusting your filters."
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.AutoStories,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = ArchiveTextMuted,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = ArchiveTextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = ArchiveTextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}
