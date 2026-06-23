package com.ninelivesaudio.app.ui.bookdetail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.ninelivesaudio.app.ui.components.BookCoverImage
import com.ninelivesaudio.app.domain.model.Chapter
import com.ninelivesaudio.app.domain.model.ListeningSession
import com.ninelivesaudio.app.domain.util.toClockString
import com.ninelivesaudio.app.domain.util.toHumanReadableString
import com.ninelivesaudio.app.ui.bookdetail.BookDetailViewModel.DownloadButtonState
import com.ninelivesaudio.app.ui.components.ContainmentFrame
import com.ninelivesaudio.app.ui.components.FluorescentSquareProgress
import com.ninelivesaudio.app.ui.theme.unhinged.*
import com.ninelivesaudio.app.ui.theme.NineLivesTheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
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
                            tint = NineLivesTheme.colors.goldFilament,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = NineLivesTheme.colors.archiveVoidDeep),
            )
        },
        containerColor = NineLivesTheme.colors.archiveVoidDeep,
    ) { innerPadding ->
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = NineLivesTheme.colors.goldFilament, strokeWidth = 2.dp)
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
                        color = NineLivesTheme.colors.archiveError,
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
                    onToggleHistory = { viewModel.toggleHistoryExpanded() },
                    onJumpToSession = { session ->
                        viewModel.jumpToSession(session, onReady = onNavigateToPlayer)
                    },
                    onDeleteForever = { viewModel.deleteForever(onDeleted = onNavigateBack) },
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
    onToggleHistory: () -> Unit,
    onJumpToSession: (ListeningSession) -> Unit,
    onDeleteForever: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(NineLivesTheme.colors.archiveVoidDeep),
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
                            .background(NineLivesTheme.colors.archiveVoidElevated),
                    ) {
                        BookCoverImage(
                            coverUrl = uiState.coverUrl,
                            contentDescription = uiState.title,
                            modifier = Modifier.fillMaxSize(),
                            title = uiState.title,
                        )
                    }

                    // Containment frame overlay
                    ContainmentFrame(
                        modifier = Modifier.matchParentSize(),
                        inset = 6.dp,
                        cornerRadius = 12.dp,
                    )

                    // Fluorescent square progress glow
                    if (uiState.hasProgress) {
                        FluorescentSquareProgress(
                            progress = uiState.progress.toFloat().coerceIn(0f, 1f),
                            modifier = Modifier.matchParentSize(),
                            cornerRadius = 12.dp,
                            padding = 10.dp,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Title
                Text(
                    text = uiState.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = NineLivesTheme.colors.archiveTextPrimary,
                    fontWeight = FontWeight.Bold,
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Author
                Text(
                    text = "by ${uiState.author}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NineLivesTheme.colors.archiveTextSecondary,
                )

                // Narrator
                uiState.narrator?.let { narrator ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Narrated by $narrator",
                        style = MaterialTheme.typography.bodySmall,
                        color = NineLivesTheme.colors.archiveTextMuted,
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
                            tint = NineLivesTheme.colors.goldFilament,
                        )
                        Text(
                            text = series,
                            style = MaterialTheme.typography.bodySmall,
                            color = NineLivesTheme.colors.goldFilament,
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
                        text = uiState.duration.toHumanReadableString(),
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
                        color = NineLivesTheme.colors.goldFilament,
                        trackColor = NineLivesTheme.colors.archiveOutline.copy(alpha = 0.3f),
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    val currentDuration = uiState.duration * uiState.progress
                    Text(
                        text = "${uiState.progressPercent}% complete (${currentDuration.toHumanReadableString()} / ${uiState.duration.toHumanReadableString()})",
                        style = MaterialTheme.typography.bodySmall,
                        color = NineLivesTheme.colors.archiveTextSecondary,
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
                        color = NineLivesTheme.colors.archiveInfo.copy(alpha = 0.15f),
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
                                tint = NineLivesTheme.colors.archiveInfo,
                            )
                            Text(
                                text = "Downloaded",
                                style = MaterialTheme.typography.labelMedium,
                                color = NineLivesTheme.colors.archiveInfo,
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
                    enabled = canPlayBook(uiState.isLocal, uiState.isDownloaded, uiState.isArchived),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NineLivesTheme.colors.goldFilament,
                        contentColor = NineLivesTheme.colors.onAccent,
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

                // Archived: the source folder was unscanned, so the audio file is
                // gone. Surface that and offer a permanent delete.
                if (uiState.isArchived) {
                    Text(
                        text = "Source file no longer available",
                        style = MaterialTheme.typography.bodySmall,
                        color = NineLivesTheme.colors.archiveTextMuted,
                    )
                    OutlinedButton(
                        onClick = onDeleteForever,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = NineLivesTheme.colors.archiveError,
                        ),
                        contentPadding = PaddingValues(vertical = 14.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Delete forever",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                        )
                    }
                }

                // Download button (hidden for scanned-local books — they're
                // already on-device and the server doesn't know their ids).
                if (!uiState.isLocal) {
                    DownloadButton(
                        downloadState = uiState.downloadState,
                        downloadProgress = uiState.downloadProgress,
                        onDownload = onDownload,
                        onDeleteDownload = onDeleteDownload,
                    )
                }
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
                        color = NineLivesTheme.colors.goldFilament,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val descColor = NineLivesTheme.colors.archiveTextSecondary.toArgb()
                    val linkColor = NineLivesTheme.colors.goldFilament.toArgb()
                    AndroidView(
                        factory = { context ->
                            android.widget.TextView(context).apply {
                                setTextColor(descColor)
                                textSize = 14f
                                setLineSpacing(4f, 1f)
                                setLinkTextColor(linkColor)
                            }
                        },
                        update = { textView ->
                            textView.text = android.text.Html.fromHtml(
                                desc,
                                android.text.Html.FROM_HTML_MODE_COMPACT,
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // ─── Chapters ───────────────────────────────────────────────
        if (uiState.chapters.isNotEmpty()) {
            item {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    color = NineLivesTheme.colors.archiveVoidElevated,
                )
                Text(
                    text = "Chapters (${uiState.chapters.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = NineLivesTheme.colors.goldFilament,
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

        // ─── Listening History (collapsible) ─────────────────────────
        item {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                color = NineLivesTheme.colors.archiveVoidElevated,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleHistory)
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Listening History",
                    style = MaterialTheme.typography.titleSmall,
                    color = NineLivesTheme.colors.goldFilament,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.weight(1f),
                )
                if (uiState.isHistoryLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = NineLivesTheme.colors.goldFilament,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = if (uiState.isHistoryExpanded)
                            Icons.Outlined.ExpandLess
                        else
                            Icons.Outlined.ExpandMore,
                        contentDescription = if (uiState.isHistoryExpanded) "Collapse" else "Expand",
                        tint = NineLivesTheme.colors.goldFilament,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        if (uiState.isHistoryExpanded) {
            if (uiState.listeningSessions.isEmpty() && !uiState.isHistoryLoading) {
                item {
                    Text(
                        text = "No listening sessions found",
                        style = MaterialTheme.typography.bodySmall,
                        color = NineLivesTheme.colors.archiveTextMuted,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            } else {
                itemsIndexed(
                    items = uiState.listeningSessions,
                    key = { _, session -> session.id },
                ) { _, session ->
                    ListeningSessionRow(
                        session = session,
                        chapters = uiState.chapters,
                        onClick = { onJumpToSession(session) },
                    )
                }
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
                colors = ButtonDefaults.outlinedButtonColors(contentColor = NineLivesTheme.colors.archiveInfo),
                border = ButtonDefaults.outlinedButtonBorder(true).copy(
                    brush = androidx.compose.ui.graphics.SolidColor(NineLivesTheme.colors.archiveInfo.copy(alpha = 0.5f)),
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
                    disabledContentColor = NineLivesTheme.colors.archiveTextSecondary,
                ),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(vertical = 14.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = NineLivesTheme.colors.archiveTextSecondary,
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
                        disabledContentColor = NineLivesTheme.colors.archiveInfo,
                    ),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(vertical = 14.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = NineLivesTheme.colors.archiveInfo,
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
                    color = NineLivesTheme.colors.archiveInfo,
                    trackColor = NineLivesTheme.colors.archiveVoidElevated,
                )
            }
        }

        DownloadButtonState.PAUSED -> {
            OutlinedButton(
                onClick = onDownload,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = NineLivesTheme.colors.goldFilament),
                border = ButtonDefaults.outlinedButtonBorder(true).copy(
                    brush = androidx.compose.ui.graphics.SolidColor(NineLivesTheme.colors.goldFilament.copy(alpha = 0.5f)),
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
                colors = ButtonDefaults.outlinedButtonColors(contentColor = NineLivesTheme.colors.archiveSuccess),
                border = ButtonDefaults.outlinedButtonBorder(true).copy(
                    brush = androidx.compose.ui.graphics.SolidColor(NineLivesTheme.colors.archiveSuccess.copy(alpha = 0.3f)),
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
            tint = NineLivesTheme.colors.archiveTextMuted,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = NineLivesTheme.colors.archiveTextMuted,
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
            color = NineLivesTheme.colors.archiveTextMuted,
            modifier = Modifier.width(28.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = chapter.title,
                style = MaterialTheme.typography.bodySmall,
                color = NineLivesTheme.colors.archiveTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = chapter.duration.toHumanReadableString(),
            style = MaterialTheme.typography.labelSmall,
            color = NineLivesTheme.colors.archiveTextMuted,
        )
    }
}

// ─── Listening Session Row ───────────────────────────────────────────────

@Composable
private fun ListeningSessionRow(
    session: ListeningSession,
    chapters: List<Chapter>,
    onClick: () -> Unit,
) {
    val chapterTitle = remember(session.currentTime, chapters) {
        val posSeconds = session.currentTime.inWholeSeconds.toDouble()
        chapters.firstOrNull { posSeconds >= it.start && posSeconds < it.end }?.title
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Date
        Text(
            text = formatSessionDate(session.startedAt),
            style = MaterialTheme.typography.labelSmall,
            color = NineLivesTheme.colors.archiveTextSecondary,
            modifier = Modifier.width(48.dp),
        )

        // Details
        Column(modifier = Modifier.weight(1f)) {
            if (chapterTitle != null) {
                Text(
                    text = chapterTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = NineLivesTheme.colors.archiveTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "at ${session.currentTime.toClockString()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = NineLivesTheme.colors.archiveTextMuted,
                )
                Text(
                    text = "\u00B7",
                    style = MaterialTheme.typography.labelSmall,
                    color = NineLivesTheme.colors.archiveTextMuted,
                )
                Text(
                    text = "${session.timeListening.toHumanReadableString()} listened",
                    style = MaterialTheme.typography.labelSmall,
                    color = NineLivesTheme.colors.archiveTextMuted,
                )
            }
        }

        // Jump indicator
        Icon(
            Icons.Outlined.PlayArrow,
            contentDescription = "Jump to position",
            modifier = Modifier.size(16.dp),
            tint = NineLivesTheme.colors.goldFilamentDim,
        )
    }
}

// ─── Formatting Helpers ───────────────────────────────────────────────────

private fun formatSessionDate(epochMillis: Long): String {
    if (epochMillis <= 0) return ""
    return try {
        val formatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)
        val instant = Instant.ofEpochMilli(epochMillis)
        instant.atZone(ZoneId.systemDefault()).format(formatter)
    } catch (_: Exception) {
        ""
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
