package com.ninelivesaudio.app.ui.bookdetail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.ninelivesaudio.app.domain.model.Chapter
import com.ninelivesaudio.app.ui.bookdetail.BookDetailViewModel.DownloadButtonState
import com.ninelivesaudio.app.ui.components.ContainmentFrame
import com.ninelivesaudio.app.ui.components.ContainmentProgressRing
import com.ninelivesaudio.app.ui.components.RingStyle
import com.ninelivesaudio.app.ui.theme.unhinged.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.time.Duration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    bookId: String,
    onNavigateBack: () -> Unit = {},
    onNavigateToPlayer: () -> Unit = {},
    viewModel: BookDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = GoldFilament,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ArchiveVoidDeep),
            )
        },
        containerColor = ArchiveVoidDeep,
    ) { innerPadding ->
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = GoldFilament, strokeWidth = 2.dp)
                }
            }
            uiState.errorMessage != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = uiState.errorMessage ?: "Error",
                        color = ArchiveError,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            else -> {
                BookDetailContent(
                    uiState = uiState,
                    onPlayBook = { viewModel.playBook(onReady = onNavigateToPlayer) },
                    onDownload = { viewModel.downloadBook() },
                    onDeleteDownload = { viewModel.deleteDownload() },
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }
    }
}

@Composable
private fun BookDetailContent(
    uiState: BookDetailViewModel.UiState,
    onPlayBook: () -> Unit,
    onDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(ArchiveVoidDeep),
        contentPadding = PaddingValues(bottom = 100.dp),
    ) {
        // ─── Cover + Core Metadata ──────────────────────────────────
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Cover art with containment frame + progress ring
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .aspectRatio(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    // Cover image inset for ring clearance
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .padding(10.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(ArchiveVoidElevated),
                    ) {
                        if (!uiState.coverUrl.isNullOrEmpty()) {
                            AsyncImage(
                                model = uiState.coverUrl,
                                contentDescription = uiState.title,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }

                    // Containment frame overlay
                    ContainmentFrame(
                        modifier = Modifier.matchParentSize(),
                        inset = 6.dp,
                        cornerRadius = 12.dp,
                    )

                    // Containment Halo progress ring
                    if (uiState.hasProgress) {
                        ContainmentProgressRing(
                            progress = uiState.progress.toFloat().coerceIn(0f, 1f),
                            modifier = Modifier.matchParentSize(),
                            style = RingStyle.BookDetail,
                            progressColor = GoldFilament,
                            trackColor = ArchiveOutline,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Title
                Text(
                    text = uiState.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = ArchiveTextPrimary,
                    fontWeight = FontWeight.Bold,
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Author
                Text(
                    text = "by ${uiState.author}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ArchiveTextSecondary,
                )

                // Narrator
                uiState.narrator?.let { narrator ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Narrated by $narrator",
                        style = MaterialTheme.typography.bodySmall,
                        color = ArchiveTextMuted,
                    )
                }

                // Series
                uiState.seriesDisplay?.let { series ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Outlined.CollectionsBookmark,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = GoldFilament,
                        )
                        Text(
                            text = series,
                            style = MaterialTheme.typography.bodySmall,
                            color = GoldFilament,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Duration + Added date
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    MetadataChip(
                        icon = Icons.Outlined.Schedule,
                        text = formatDuration(uiState.duration),
                    )
                    uiState.addedAt?.let { epochMillis ->
                        MetadataChip(
                            icon = Icons.Outlined.CalendarToday,
                            text = formatDate(epochMillis),
                        )
                    }
                }
            }
        }

        // ─── Progress Section ───────────────────────────────────────
        if (uiState.hasProgress) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    LinearProgressIndicator(
                        progress = { uiState.progress.coerceIn(0.0, 1.0).toFloat() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = GoldFilament,
                        trackColor = ArchiveOutline.copy(alpha = 0.3f),
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    val currentDuration = uiState.duration * uiState.progress
                    Text(
                        text = "${uiState.progressPercent}% complete (${formatDuration(currentDuration)} / ${formatDuration(uiState.duration)})",
                        style = MaterialTheme.typography.bodySmall,
                        color = ArchiveTextSecondary,
                    )
                }
            }
        }

        // ─── Downloaded Badge ───────────────────────────────────────
        if (uiState.isDownloaded) {
            item {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                ) {
                    Surface(
                        color = ArchiveInfo.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                Icons.Outlined.CloudDownload,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = ArchiveInfo,
                            )
                            Text(
                                text = "Downloaded",
                                style = MaterialTheme.typography.labelMedium,
                                color = ArchiveInfo,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }

        // ─── Action Buttons ─────────────────────────────────────────
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Continue / Play button
                Button(
                    onClick = onPlayBook,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GoldFilament,
                        contentColor = ArchiveVoidDeep,
                    ),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(vertical = 14.dp),
                ) {
                    Icon(
                        Icons.Outlined.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (uiState.hasProgress) "Continue" else "Play",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                }

                // Download button
                DownloadButton(
                    downloadState = uiState.downloadState,
                    downloadProgress = uiState.downloadProgress,
                    onDownload = onDownload,
                    onDeleteDownload = onDeleteDownload,
                )
            }
        }

        // ─── Description ────────────────────────────────────────────
        uiState.description?.let { desc ->
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = "Details",
                        style = MaterialTheme.typography.titleSmall,
                        color = GoldFilament,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = ArchiveTextSecondary,
                        lineHeight = 20.sp,
                    )
                }
            }
        }

        // ─── Chapters ───────────────────────────────────────────────
        if (uiState.chapters.isNotEmpty()) {
            item {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    color = ArchiveVoidElevated,
                )
                Text(
                    text = "Chapters (${uiState.chapters.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = GoldFilament,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }

            itemsIndexed(
                items = uiState.chapters,
                key = { _, ch -> ch.id },
            ) { index, chapter ->
                ChapterRow(
                    index = index + 1,
                    chapter = chapter,
                )
            }
        }
    }
}

// ─── Download Button ──────────────────────────────────────────────────────

@Composable
private fun DownloadButton(
    downloadState: DownloadButtonState,
    downloadProgress: Int,
    onDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
) {
    when (downloadState) {
        DownloadButtonState.NONE -> {
            OutlinedButton(
                onClick = onDownload,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = ArchiveInfo),
                border = ButtonDefaults.outlinedButtonBorder(true).copy(
                    brush = androidx.compose.ui.graphics.SolidColor(ArchiveInfo.copy(alpha = 0.5f)),
                ),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(vertical = 14.dp),
            ) {
                Icon(
                    Icons.Outlined.CloudDownload,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Download",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
            }
        }

        DownloadButtonState.QUEUED -> {
            OutlinedButton(
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    disabledContentColor = ArchiveTextSecondary,
                ),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(vertical = 14.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = ArchiveTextSecondary,
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Queued\u2026",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
            }
        }

        DownloadButtonState.DOWNLOADING -> {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        disabledContentColor = ArchiveInfo,
                    ),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(vertical = 14.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = ArchiveInfo,
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Downloading $downloadProgress%",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                    )
                }
                val animatedProgress by animateFloatAsState(
                    targetValue = downloadProgress / 100f,
                    label = "downloadProgress",
                )
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = ArchiveInfo,
                    trackColor = ArchiveVoidElevated,
                )
            }
        }

        DownloadButtonState.PAUSED -> {
            OutlinedButton(
                onClick = onDownload,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = GoldFilament),
                border = ButtonDefaults.outlinedButtonBorder(true).copy(
                    brush = androidx.compose.ui.graphics.SolidColor(GoldFilament.copy(alpha = 0.5f)),
                ),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(vertical = 14.dp),
            ) {
                Icon(
                    Icons.Outlined.Pause,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Paused \u2014 Tap to Retry",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
            }
        }

        DownloadButtonState.COMPLETED -> {
            OutlinedButton(
                onClick = onDeleteDownload,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = ArchiveSuccess),
                border = ButtonDefaults.outlinedButtonBorder(true).copy(
                    brush = androidx.compose.ui.graphics.SolidColor(ArchiveSuccess.copy(alpha = 0.3f)),
                ),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(vertical = 14.dp),
            ) {
                Icon(
                    Icons.Outlined.CloudDone,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Downloaded",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
            }
        }
    }
}

// ─── Metadata Chip ────────────────────────────────────────────────────────

@Composable
private fun MetadataChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = ArchiveTextMuted,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = ArchiveTextMuted,
            fontSize = 12.sp,
        )
    }
}

// ─── Chapter Row ──────────────────────────────────────────────────────────

@Composable
private fun ChapterRow(
    index: Int,
    chapter: Chapter,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "$index",
            style = MaterialTheme.typography.labelMedium,
            color = ArchiveTextMuted,
            modifier = Modifier.width(28.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = chapter.title,
                style = MaterialTheme.typography.bodySmall,
                color = ArchiveTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = formatDuration(chapter.duration),
            style = MaterialTheme.typography.labelSmall,
            color = ArchiveTextMuted,
        )
    }
}

// ─── Formatting Helpers ───────────────────────────────────────────────────

private fun formatDuration(duration: Duration): String {
    val totalSeconds = duration.inWholeSeconds.coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60

    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${secs}s"
        else -> "${secs}s"
    }
}

private fun formatDate(epochMillis: Long): String {
    if (epochMillis <= 0) return ""
    return try {
        // Use thread-safe DateTimeFormatter instead of SimpleDateFormat
        val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
        val instant = Instant.ofEpochMilli(epochMillis)
        instant.atZone(ZoneId.systemDefault()).format(formatter)
    } catch (_: Exception) {
        ""
    }
}
