package com.ninelivesaudio.app.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.ninelivesaudio.app.domain.model.DownloadStatus
import com.ninelivesaudio.app.domain.util.toDisplaySize

import com.ninelivesaudio.app.ui.copy.unhinged.CopyEngine
import com.ninelivesaudio.app.ui.copy.unhinged.CopyStyleGuide
import com.ninelivesaudio.app.ui.theme.unhinged.*

@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ArchiveVoidDeep),
    ) {
        // ─── Header ──────────────────────────────────────────────────────
        DownloadsHeader()

        if (uiState.showEmptyState) {
            EmptyDownloadsState()
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // ── Active Downloads Section ──────────────────────────
                if (uiState.activeDownloads.isNotEmpty()) {
                    item {
                        SectionHeader(title = "Active Downloads")
                    }

                    items(
                        items = uiState.activeDownloads,
                        key = { it.download.id },
                    ) { item ->
                        ActiveDownloadCard(
                            item = item,
                            onPause = { viewModel.pauseDownload(item.download.id) },
                            onResume = { viewModel.resumeDownload(item.download.id) },
                            onCancel = { viewModel.cancelDownload(item.download.id) },
                        )
                    }
                }

                // ── Completed Downloads Section ───────────────────────
                if (uiState.completedDownloads.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp, bottom = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SectionHeader(title = "Completed")

                            TextButton(onClick = { viewModel.clearCompleted() }) {
                                Text(
                                    text = "Clear All",
                                    color = GoldFilament,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }

                    items(
                        items = uiState.completedDownloads,
                        key = { it.download.id },
                    ) { item ->
                        CompletedDownloadCard(
                            item = item,
                            onDelete = { viewModel.deleteDownload(item.download.audioBookId) },
                        )
                    }
                }
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  Header
// ═════════════════════════════════════════════════════════════════════════════

@Composable
private fun DownloadsHeader() {
    val subtitle = CopyEngine.getSubtitle(
        CopyStyleGuide.Downloads.DOWNLOADS_NAV_RITUAL,
        CopyStyleGuide.Downloads.DOWNLOADS_NAV_UNHINGED,
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
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
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = CopyStyleGuide.Downloads.DOWNLOADS_NAV,
                color = GoldFilament,
                fontSize = 28.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 4.sp,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = ArchiveTextSecondary,
                )
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  Section Header
// ═════════════════════════════════════════════════════════════════════════════

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = ArchiveTextSecondary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

// ═════════════════════════════════════════════════════════════════════════════
//  Active Download Card
// ═════════════════════════════════════════════════════════════════════════════

@Composable
private fun ActiveDownloadCard(
    item: DownloadsViewModel.DownloadUiItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    val download = item.download
    val progress = download.progress.toFloat() / 100f

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = ArchiveVoidSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Cover thumbnail
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(ArchiveVoidElevated),
                ) {
                    if (!item.coverPath.isNullOrEmpty()) {
                        AsyncImage(
                            model = item.coverPath,
                            contentDescription = download.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }

                // Title + status
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = download.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ArchiveTextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Status badge
                        val (statusText, statusColor) = when (download.status) {
                            DownloadStatus.Queued -> "Queued" to ArchiveTextMuted
                            DownloadStatus.Downloading -> "Downloading" to GoldFilament
                            DownloadStatus.Paused -> "Paused" to ArchiveWarning
                            DownloadStatus.Failed -> "Failed" to ArchiveError
                            else -> "" to ArchiveTextMuted
                        }
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                            fontSize = 11.sp,
                        )

                        // Size
                        Text(
                            text = download.sizeDisplay,
                            style = MaterialTheme.typography.labelSmall,
                            color = ArchiveTextMuted,
                            fontSize = 11.sp,
                        )
                    }

                    // Error message
                    if (download.status == DownloadStatus.Failed && !download.errorMessage.isNullOrEmpty()) {
                        Text(
                            text = download.errorMessage,
                            style = MaterialTheme.typography.labelSmall,
                            color = ArchiveError,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 10.sp,
                        )
                    }
                }

                // Action buttons
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (download.status == DownloadStatus.Downloading || download.status == DownloadStatus.Queued) {
                        IconButton(
                            onClick = onPause,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Pause,
                                contentDescription = "Pause",
                                tint = ArchiveTextSecondary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    if (download.status == DownloadStatus.Paused || download.status == DownloadStatus.Failed) {
                        IconButton(
                            onClick = onResume,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                Icons.Outlined.PlayArrow,
                                contentDescription = "Resume",
                                tint = GoldFilament,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = "Cancel",
                            tint = ArchiveError,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            // Progress bar
            if (download.status == DownloadStatus.Downloading || download.status == DownloadStatus.Queued) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = GoldFilament,
                    trackColor = ArchiveOutline.copy(alpha = 0.3f),
                )

                // Progress text
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "${download.progress.toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = ArchiveTextMuted,
                        fontSize = 10.sp,
                    )
                    Text(
                        text = "${download.downloadedBytes.toDisplaySize()} / ${download.totalBytes.toDisplaySize()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = ArchiveTextMuted,
                        fontSize = 10.sp,
                    )
                }
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  Completed Download Card
// ═════════════════════════════════════════════════════════════════════════════

@Composable
private fun CompletedDownloadCard(
    item: DownloadsViewModel.DownloadUiItem,
    onDelete: () -> Unit,
) {
    val download = item.download

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = ArchiveVoidSurface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Cover thumbnail
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(ArchiveVoidElevated),
            ) {
                if (!item.coverPath.isNullOrEmpty()) {
                    AsyncImage(
                        model = item.coverPath,
                        contentDescription = download.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                    )
                }
            }

            // Title + completed info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = download.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ArchiveTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = ArchiveSuccess,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = "Downloaded",
                        style = MaterialTheme.typography.labelSmall,
                        color = ArchiveSuccess,
                        fontSize = 11.sp,
                    )
                    Text(
                        text = download.sizeDisplay,
                        style = MaterialTheme.typography.labelSmall,
                        color = ArchiveTextMuted,
                        fontSize = 11.sp,
                    )
                }
            }

            // Delete button
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "Delete download",
                    tint = ArchiveError.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  Empty State
// ═════════════════════════════════════════════════════════════════════════════

@Composable
private fun EmptyDownloadsState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.CloudDownload,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = ArchiveTextMuted,
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = CopyStyleGuide.Downloads.DOWNLOADS_NAV,
            style = MaterialTheme.typography.titleMedium,
            color = ArchiveTextPrimary,
            fontWeight = FontWeight.SemiBold,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = CopyEngine.getEmptyStateFlavor(
                CopyStyleGuide.EmptyStates.EMPTY_DOWNLOADS_RITUAL,
                CopyStyleGuide.EmptyStates.EMPTY_DOWNLOADS_UNHINGED,
            ) ?: CopyStyleGuide.EmptyStates.EMPTY_DOWNLOADS_NORMAL,
            style = MaterialTheme.typography.bodySmall,
            color = ArchiveTextMuted,
            textAlign = TextAlign.Center,
        )
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  Helpers
// ═════════════════════════════════════════════════════════════════════════════

