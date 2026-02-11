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
import com.ninelivesaudio.app.ui.components.StatusPill
import com.ninelivesaudio.app.ui.theme.*

@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(VoidDeep),
    ) {
        // ─── Header ──────────────────────────────────────────────────────
        DownloadsHeader(connectionStatus = uiState.connectionStatus)

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
                                    color = SigilGold,
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
private fun DownloadsHeader(
    connectionStatus: com.ninelivesaudio.app.service.ConnectivityMonitor.ConnectionStatus,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Downloads",
            style = MaterialTheme.typography.headlineSmall,
            color = Starlight,
            fontWeight = FontWeight.Bold,
        )
        StatusPill(connectionStatus = connectionStatus)
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
        color = Mist,
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
        color = VoidSurface,
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
                        .background(VoidElevated),
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
                        color = Starlight,
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
                            DownloadStatus.Queued -> "Queued" to MistFaint
                            DownloadStatus.Downloading -> "Downloading" to SigilGold
                            DownloadStatus.Paused -> "Paused" to CosmicWarning
                            DownloadStatus.Failed -> "Failed" to CosmicError
                            else -> "" to MistFaint
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
                            color = MistFaint,
                            fontSize = 11.sp,
                        )
                    }

                    // Error message
                    if (download.status == DownloadStatus.Failed && !download.errorMessage.isNullOrEmpty()) {
                        Text(
                            text = download.errorMessage,
                            style = MaterialTheme.typography.labelSmall,
                            color = CosmicError,
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
                                tint = Mist,
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
                                tint = SigilGold,
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
                            tint = CosmicError,
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
                    color = SigilGold,
                    trackColor = ProgressTrack,
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
                        color = MistFaint,
                        fontSize = 10.sp,
                    )
                    Text(
                        text = formatDownloadSize(download.downloadedBytes, download.totalBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MistFaint,
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
        color = VoidSurface,
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
                    .background(VoidElevated),
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

            // Title + completed info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = download.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Starlight,
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
                        tint = CosmicSuccess,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = "Downloaded",
                        style = MaterialTheme.typography.labelSmall,
                        color = CosmicSuccess,
                        fontSize = 11.sp,
                    )
                    Text(
                        text = download.sizeDisplay,
                        style = MaterialTheme.typography.labelSmall,
                        color = MistFaint,
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
                    tint = CosmicError.copy(alpha = 0.7f),
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
            tint = MistFaint,
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "No Downloads",
            style = MaterialTheme.typography.titleMedium,
            color = Starlight,
            fontWeight = FontWeight.SemiBold,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Download audiobooks from your Library for offline listening",
            style = MaterialTheme.typography.bodySmall,
            color = MistFaint,
            textAlign = TextAlign.Center,
        )
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  Helpers
// ═════════════════════════════════════════════════════════════════════════════

private fun formatDownloadSize(downloaded: Long, total: Long): String {
    fun formatBytes(bytes: Long): String = when {
        bytes > 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes > 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes > 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
    return "${formatBytes(downloaded)} / ${formatBytes(total)}"
}
