package com.ninelivesaudio.app.ui.library

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.ninelivesaudio.app.ui.components.CornerSigils
import com.ninelivesaudio.app.ui.components.CosmicProgressRing
import com.ninelivesaudio.app.ui.components.StatusPill
import com.ninelivesaudio.app.ui.animation.unhinged.anomalies.AnomalyHost
import com.ninelivesaudio.app.ui.animation.unhinged.anomalies.AnomalyTriggerContext
import com.ninelivesaudio.app.ui.copy.unhinged.CopyEngine
import com.ninelivesaudio.app.ui.copy.unhinged.CopyStyleGuide
import com.ninelivesaudio.app.ui.theme.unhinged.*

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
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                top = 8.dp,
                                bottom = 100.dp,
                            ),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            itemsIndexed(
                                items = uiState.filteredBooks,
                                key = { _, book -> book.id },
                            ) { index, book ->
                                ArchiveBookTile(
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
            .padding(horizontal = 20.dp, vertical = 12.dp),
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
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    CopyEngine.getSearchHint(
                        CopyStyleGuide.Search.SEARCH_HINT_NORMAL,
                        CopyStyleGuide.Search.SEARCH_HINT_RITUAL,
                        CopyStyleGuide.Search.SEARCH_HINT_UNHINGED,
                    ),
                    color = ArchiveTextMuted,
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

// ─── Archive Book Tile ───────────────────────────────────────────────────

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

            // Progress ring
            CosmicProgressRing(
                progress = animatedProgress,
                modifier = Modifier.matchParentSize().padding(3.dp),
                strokeWidth = 4.dp,
                progressColor = GoldFilament,
                trackColor = ArchiveOutline.copy(alpha = 0.3f),
                glowStrength = 0.4f,
                showEndCapDot = false,
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
        uiState.allBooks.isEmpty() ->
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
