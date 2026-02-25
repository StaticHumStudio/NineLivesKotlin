package com.ninelivesaudio.app.ui.library

// Force rebuild v3 — merged PR14+PR15+PR16
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ninelivesaudio.app.ui.components.BookCoverImage
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.ui.components.ContainmentFrame
import com.ninelivesaudio.app.ui.components.CornerSigils
import com.ninelivesaudio.app.ui.components.FluorescentSquareProgress

import com.ninelivesaudio.app.ui.animation.unhinged.anomalies.AnomalyHost
import com.ninelivesaudio.app.ui.animation.unhinged.anomalies.AnomalyTriggerContext
import com.ninelivesaudio.app.ui.copy.unhinged.CopyEngine
import com.ninelivesaudio.app.ui.copy.unhinged.CopyStyleGuide
import com.ninelivesaudio.app.ui.copy.unhinged.catalog.BookWhisperCatalog
import com.ninelivesaudio.app.ui.theme.unhinged.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onNavigateToBookDetail: (String) -> Unit = {},
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val whisperEpoch by viewModel.whisperEpoch.collectAsStateWithLifecycle()

    // Increment whisper epoch each time this screen enters composition
    // (i.e. each time the user navigates to the Library tab).
    LaunchedEffect(Unit) {
        viewModel.incrementWhisperEpoch()
    }

    // Flatten grouped items only when groupedSections or expandedGroups change
    val groupedListItems = remember(uiState.groupedSections, uiState.expandedGroups) {
        flattenGroupedItems(uiState.groupedSections, uiState.expandedGroups)
    }

    AnomalyHost(
        currentContext = AnomalyTriggerContext.LIBRARY,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(ArchiveVoidDeep)
        ) {
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
                        Column {
                            ArchiveControlDeck(
                                uiState = uiState,
                                onSearchQueryChanged = viewModel::onSearchQueryChanged,
                                onLibraryTabChanged = viewModel::onLibraryTabChanged,
                                onViewModeChanged = viewModel::onViewModeChanged,
                                onSortModeChanged = viewModel::onSortModeChanged,
                                onHideFinishedChanged = viewModel::onHideFinishedChanged,
                                onShowDownloadedOnlyChanged = viewModel::onShowDownloadedOnlyChanged,
                                onResetFilters = viewModel::resetFilters,
                            )
                            EmptyState(uiState)
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 18.dp,
                                end = 18.dp,
                                top = 0.dp,
                                bottom = 100.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // ─── Scrollable Control Deck ────────────────────
                            item(key = "archive-control-deck") {
                                ArchiveControlDeck(
                                    uiState = uiState,
                                    onSearchQueryChanged = viewModel::onSearchQueryChanged,
                                    onLibraryTabChanged = viewModel::onLibraryTabChanged,
                                    onViewModeChanged = viewModel::onViewModeChanged,
                                    onSortModeChanged = viewModel::onSortModeChanged,
                                    onHideFinishedChanged = viewModel::onHideFinishedChanged,
                                    onShowDownloadedOnlyChanged = viewModel::onShowDownloadedOnlyChanged,
                                    onResetFilters = viewModel::resetFilters,
                                )
                            }

                            if (uiState.viewMode == ViewMode.ALL) {
                                // Flat list in ALL mode
                                itemsIndexed(
                                    items = uiState.filteredBooks,
                                    key = { _, book -> book.id },
                                ) { index, book ->
                                    ArchiveBookListItem(
                                        book = book,
                                        index = index,
                                        whisperEpoch = whisperEpoch,
                                        onClick = { onNavigateToBookDetail(book.id) },
                                    )
                                }
                            } else {
                                // Grouped expandable list in SERIES / AUTHOR / GENRE modes
                                itemsIndexed(
                                    items = groupedListItems,
                                    key = { _, item ->
                                        when (item) {
                                            is LibraryListItem.GroupHeader -> "header-${item.groupKey}"
                                            is LibraryListItem.BookRow -> "book-${item.groupKey}-${item.book.id}"
                                        }
                                    },
                                ) { index, item ->
                                    when (item) {
                                        is LibraryListItem.GroupHeader -> GroupHeaderRow(
                                            title = item.title,
                                            count = item.count,
                                            isExpanded = item.isExpanded,
                                            onClick = { viewModel.onGroupExpansionToggled(item.groupKey) },
                                        )
                                        is LibraryListItem.BookRow -> ArchiveBookListItem(
                                            book = item.book,
                                            index = index,
                                            whisperEpoch = whisperEpoch,
                                            onClick = { onNavigateToBookDetail(item.book.id) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── Archive Control Deck ─────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArchiveControlDeck(
    uiState: LibraryViewModel.UiState,
    onSearchQueryChanged: (String) -> Unit,
    onLibraryTabChanged: (LibraryTab) -> Unit,
    onViewModeChanged: (ViewMode) -> Unit,
    onSortModeChanged: (SortMode) -> Unit,
    onHideFinishedChanged: (Boolean) -> Unit,
    onShowDownloadedOnlyChanged: (Boolean) -> Unit,
    onResetFilters: () -> Unit,
) {
    val outerHorizontalPadding = 2.dp // Minimal — LazyColumn contentPadding provides 18dp already
    val sectionSpacing = 8.dp
    val archiveSubtitle = CopyEngine.getSubtitle(
        ritualSubtitle = "Cataloged echoes, awaiting selection.",
        unhingedSubtitle = "Every spine twitches if you stare long enough.",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = outerHorizontalPadding)
            .padding(top = 10.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(sectionSpacing),
    ) {
        // Header card: "The Archive" title + subtitle flavor text
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            ArchiveVoidElevated,
                            ArchiveVoidBase,
                            ArchiveVoidSurface,
                        )
                    )
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "The Archive",
                    color = GoldFilament,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 4.sp,
                )
                archiveSubtitle?.let { subtitle ->
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = ArchiveTextSecondary,
                    )
                }
            }
        }

        RelicSearchBar(
            query = uiState.searchQuery,
            onQueryChange = onSearchQueryChanged,
            modifier = Modifier.fillMaxWidth(),
        )

        StoneTabsRow(
            selectedTab = uiState.selectedTab,
            onTabSelected = onLibraryTabChanged,
            modifier = Modifier.fillMaxWidth(),
        )

        LibraryFiltersRow(
            uiState = uiState,
            onViewModeChanged = onViewModeChanged,
            onSortModeChanged = onSortModeChanged,
            onHideFinishedChanged = onHideFinishedChanged,
            onShowDownloadedOnlyChanged = onShowDownloadedOnlyChanged,
            onResetFilters = onResetFilters,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ─── Relic Search Bar ────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RelicSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .shadow(
                elevation = if (isFocused) 6.dp else 2.dp,
                shape = RoundedCornerShape(12.dp),
                ambientColor = GoldFilament.copy(alpha = if (isFocused) 0.35f else 0.12f),
                spotColor = GoldFilament.copy(alpha = if (isFocused) 0.35f else 0.12f),
            ),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = ArchiveTextPrimary),
        cursorBrush = SolidColor(GoldFilament),
        interactionSource = interactionSource,
        decorationBox = { innerTextField ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = query,
                innerTextField = innerTextField,
                enabled = true,
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                interactionSource = interactionSource,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                placeholder = {
                    Text(
                        CopyEngine.getSearchHint(
                            normalHint = "Search titles, authors, or series",
                            ritualHint = "Seek titles, authors, or bloodlines",
                            unhingedHint = "Whisper a title and see what answers back",
                        ),
                        color = if (isFocused) ArchiveTextSecondary else ArchiveTextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Search,
                        contentDescription = null,
                        tint = if (isFocused) GoldFilament else ArchiveTextSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(
                            onClick = { onQueryChange("") },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Clear,
                                contentDescription = "Clear search",
                                tint = ArchiveTextMuted,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                } else null,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = GoldFilament.copy(alpha = 0.95f),
                    unfocusedBorderColor = ArchiveOutline,
                    focusedContainerColor = ArchiveVoidSurface,
                    unfocusedContainerColor = ArchiveVoidSurface,
                    focusedTextColor = ArchiveTextPrimary,
                    unfocusedTextColor = ArchiveTextPrimary,
                    cursorColor = GoldFilament,
                ),
                container = {
                    OutlinedTextFieldDefaults.ContainerBox(
                        enabled = true,
                        isError = false,
                        interactionSource = interactionSource,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GoldFilament.copy(alpha = 0.95f),
                            unfocusedBorderColor = ArchiveOutline,
                            focusedContainerColor = ArchiveVoidSurface,
                            unfocusedContainerColor = ArchiveVoidSurface,
                        ),
                    )
                },
            )
        },
    )
}

// ─── Stone Tabs Row ──────────────────────────────────────────────────────

@Composable
private fun StoneTabsRow(
    selectedTab: LibraryTab,
    onTabSelected: (LibraryTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LibraryTab.entries.forEach { tab ->
            val isSelected = tab == selectedTab
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onTabSelected(tab) },
                shape = RoundedCornerShape(8.dp),
                color = if (isSelected) GoldFilamentFaint else ArchiveVoidSurface,
                border = BorderStroke(
                    width = 1.dp,
                    color = if (isSelected) GoldFilament.copy(alpha = 0.6f) else ArchiveOutline,
                ),
            ) {
                Text(
                    text = tab.label,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
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

// ─── Filters / Sorting ───────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryFiltersRow(
    uiState: LibraryViewModel.UiState,
    onViewModeChanged: (ViewMode) -> Unit,
    onSortModeChanged: (SortMode) -> Unit,
    onHideFinishedChanged: (Boolean) -> Unit,
    onShowDownloadedOnlyChanged: (Boolean) -> Unit,
    onResetFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var sortExpanded by remember { mutableStateOf(false) }

    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 0.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Row 1: View mode chips + sort dropdown, horizontally scrollable
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
                                    SortMode.RECENTLY_PLAYED -> "Recently played"
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

            // Row 2: Toggle chips + Reset, horizontally scrollable
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
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

                AssistChip(
                    onClick = onResetFilters,
                    label = { Text("Reset") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.RestartAlt,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
            }
        }
    }
}

// ─── Group Header Row ────────────────────────────────────────────────────

@Composable
private fun GroupHeaderRow(
    title: String,
    count: Int,
    isExpanded: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = ArchiveVoidElevated,
        border = BorderStroke(1.dp, ArchiveOutline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = if (isExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = GoldFilament,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "$title • $count",
                style = MaterialTheme.typography.titleSmall,
                color = ArchiveTextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// ─── Archive Book List Item ──────────────────────────────────────────────

@Composable
private fun ArchiveBookListItem(
    book: AudioBook,
    index: Int,
    whisperEpoch: Int = 0,
    onClick: () -> Unit,
) {
    // progressPercent is normalized to 0–100 regardless of API format; divide back to 0–1 for the ring.
    val progress = (book.progressPercent / 100.0).toFloat().coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 800),
        label = "lib_progress_$index",
    )

    // 50% of books show a whisper; re-rolls each time Library is entered
    val showWhisper = remember(book.id, whisperEpoch) {
        BookWhisperCatalog.shouldShowWhisper(book.id, whisperEpoch)
    }
    val whisper = remember(book.id, book.currentTime, book.isFinished, whisperEpoch) {
        if (showWhisper) BookWhisperCatalog.getWhisper(book, whisperEpoch) else null
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
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
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
                    BookCoverImage(
                        coverUrl = book.coverPath,
                        contentDescription = book.title,
                        modifier = Modifier.fillMaxSize(),
                        title = book.title,
                        bookId = book.id,
                    )
                }

                // Fluorescent square progress glow
                FluorescentSquareProgress(
                    progress = animatedProgress,
                    modifier = Modifier.matchParentSize(),
                    cornerRadius = 8.dp,
                    padding = 4.dp,
                    strokeScale = 0.6f,
                )

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

                // Book whisper — time-aware atmospheric phrase (shown on ~50% of books)
                if (whisper != null) {
                    Text(
                        text = whisper,
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

// ─── Archive Book Tile (kept for reference/future use) ───────────────────

@Composable
private fun ArchiveBookTile(
    book: AudioBook,
    index: Int,
    onClick: () -> Unit,
) {
    val progress = (book.progressPercent / 100.0).toFloat().coerceIn(0f, 1f)
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
                BookCoverImage(
                    coverUrl = book.coverPath,
                    contentDescription = book.title,
                    modifier = Modifier.fillMaxSize(),
                    title = book.title,
                    bookId = book.id,
                )
            }

            // Containment frame
            ContainmentFrame(
                modifier = Modifier.matchParentSize(),
                inset = 2.dp,
                cornerRadius = 14.dp,
            )

            // Fluorescent square progress glow
            FluorescentSquareProgress(
                progress = animatedProgress,
                modifier = Modifier.matchParentSize(),
                cornerRadius = 14.dp,
                padding = 6.dp,
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
