package com.ninelivesaudio.app.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ninelivesaudio.app.ui.components.BookListItem
import com.ninelivesaudio.app.ui.components.StatusPill
import com.ninelivesaudio.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onNavigateToBookDetail: (String) -> Unit = {},
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // ─── Header ───────────────────────────────────────────────────
        LibraryHeader(uiState)

        // ─── Search Bar ───────────────────────────────────────────────
        SearchBar(
            query = uiState.searchQuery,
            onQueryChange = viewModel::onSearchQueryChanged,
        )

        // ─── Library Picker + View Mode Chips ─────────────────────────
        LibraryToolbar(
            uiState = uiState,
            onLibrarySelected = viewModel::onLibrarySelected,
            onViewModeChanged = viewModel::onViewModeChanged,
        )

        // ─── Group Filter Chips (when in Series/Author/Genre mode) ────
        AnimatedVisibility(
            visible = uiState.viewMode != ViewMode.ALL && uiState.availableGroups.isNotEmpty()
        ) {
            GroupFilterRow(
                groups = uiState.availableGroups,
                selected = uiState.selectedGroupFilter,
                onSelected = viewModel::onGroupFilterSelected,
            )
        }

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
                            bottom = 100.dp, // Space for mini player / nav bar
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(
                            items = uiState.filteredBooks,
                            key = { it.id },
                        ) { book ->
                            BookListItem(
                                book = book,
                                onClick = { onNavigateToBookDetail(book.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── Header ───────────────────────────────────────────────────────────────

@Composable
private fun LibraryHeader(uiState: LibraryViewModel.UiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Library",
            style = MaterialTheme.typography.headlineMedium,
            color = Starlight,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.weight(1f))
        StatusPill(connectionStatus = uiState.connectionStatus)
    }
}

// ─── Search Bar ───────────────────────────────────────────────────────────

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        placeholder = {
            Text("Search books...", color = MistFaint)
        },
        leadingIcon = {
            Icon(
                Icons.Outlined.Search,
                contentDescription = null,
                tint = Mist,
                modifier = Modifier.size(20.dp),
            )
        },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Outlined.Clear,
                        contentDescription = "Clear search",
                        tint = MistFaint,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        } else null,
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = SigilGold,
            unfocusedBorderColor = VoidElevated,
            focusedContainerColor = VoidSurface,
            unfocusedContainerColor = VoidSurface,
            focusedTextColor = Starlight,
            unfocusedTextColor = Starlight,
            cursorColor = SigilGold,
        ),
    )
}

// ─── Library Picker + View Mode Chips ─────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryToolbar(
    uiState: LibraryViewModel.UiState,
    onLibrarySelected: (com.ninelivesaudio.app.domain.model.Library) -> Unit,
    onViewModeChanged: (ViewMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Library picker dropdown
        if (uiState.libraries.size > 1) {
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
            ) {
                Surface(
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .clip(RoundedCornerShape(8.dp)),
                    color = VoidSurface,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = uiState.selectedLibrary?.name ?: "Select Library",
                            style = MaterialTheme.typography.labelMedium,
                            color = Starlight,
                        )
                        Icon(
                            if (expanded) Icons.Outlined.KeyboardArrowUp
                            else Icons.Outlined.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = Mist,
                        )
                    }
                }

                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    containerColor = VoidSurface,
                ) {
                    uiState.libraries.forEach { library ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    library.name,
                                    color = if (library.id == uiState.selectedLibrary?.id)
                                        SigilGold else Starlight,
                                )
                            },
                            onClick = {
                                onLibrarySelected(library)
                                expanded = false
                            },
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // View mode filter chips
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ViewMode.entries.forEach { mode ->
                val label = when (mode) {
                    ViewMode.ALL -> "All"
                    ViewMode.SERIES -> "Series"
                    ViewMode.AUTHOR -> "Authors"
                    ViewMode.GENRE -> "Genres"
                }
                val selected = uiState.viewMode == mode

                FilterChip(
                    selected = selected,
                    onClick = { onViewModeChanged(mode) },
                    label = {
                        Text(
                            text = label,
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = VoidSurface,
                        labelColor = Mist,
                        selectedContainerColor = SigilGoldFaint,
                        selectedLabelColor = SigilGold,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = selected,
                        borderColor = VoidElevated,
                        selectedBorderColor = SigilGold.copy(alpha = 0.5f),
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(32.dp),
                )
            }
        }
    }
}

// ─── Group Filter Row (horizontal scroll) ─────────────────────────────────

@Composable
private fun GroupFilterRow(
    groups: List<String>,
    selected: String?,
    onSelected: (String?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // "All" chip to clear filter
        FilterChip(
            selected = selected == null,
            onClick = { onSelected(null) },
            label = { Text("All", fontSize = 11.sp) },
            colors = FilterChipDefaults.filterChipColors(
                containerColor = VoidSurface,
                labelColor = Mist,
                selectedContainerColor = SigilGoldFaint,
                selectedLabelColor = SigilGold,
            ),
            border = FilterChipDefaults.filterChipBorder(
                enabled = true,
                selected = selected == null,
                borderColor = VoidElevated,
                selectedBorderColor = SigilGold.copy(alpha = 0.5f),
            ),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.height(30.dp),
        )

        groups.forEach { group ->
            FilterChip(
                selected = group == selected,
                onClick = { onSelected(if (group == selected) null else group) },
                label = {
                    Text(
                        text = group,
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = VoidSurface,
                    labelColor = Mist,
                    selectedContainerColor = SigilGoldFaint,
                    selectedLabelColor = SigilGold,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = group == selected,
                    borderColor = VoidElevated,
                    selectedBorderColor = SigilGold.copy(alpha = 0.5f),
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(30.dp),
            )
        }
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
            color = SigilGold,
            strokeWidth = 2.dp,
        )
    }
}

// ─── Empty State ──────────────────────────────────────────────────────────

@Composable
private fun EmptyState(uiState: LibraryViewModel.UiState) {
    val (title, subtitle) = when {
        uiState.searchQuery.isNotBlank() ->
            "No Results" to "No books match \"${uiState.searchQuery}\". Try a different search."
        uiState.connectionStatus == com.ninelivesaudio.app.service.ConnectivityMonitor.ConnectionStatus.OFFLINE ->
            "Not Connected" to "Connect to your AudioBookshelf server in Settings."
        uiState.allBooks.isEmpty() ->
            "Library Empty" to "Your library has no books yet. Add some on your server."
        else ->
            "No Books Found" to "Try changing your filters."
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
            tint = MistFaint,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Starlight,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MistFaint,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}
