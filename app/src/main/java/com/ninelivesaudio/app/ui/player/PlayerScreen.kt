package com.ninelivesaudio.app.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
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
import com.ninelivesaudio.app.domain.model.Bookmark
import com.ninelivesaudio.app.ui.components.ContainmentFrame
import com.ninelivesaudio.app.ui.components.ContainmentProgressRing
import com.ninelivesaudio.app.ui.components.RingStyle
import com.ninelivesaudio.app.domain.util.toClockString
import com.ninelivesaudio.app.ui.animation.unhinged.sigil.SigilProgressBar
import com.ninelivesaudio.app.ui.theme.unhinged.*
import kotlin.time.Duration

@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Bookmark bottom sheet
    if (uiState.showBookmarks) {
        BookmarkSheet(
            bookmarks = uiState.bookmarks,
            positionText = uiState.positionText,
            onAddBookmark = { title -> viewModel.addBookmark(title) },
            onSeekToBookmark = { bookmark -> viewModel.seekToBookmark(bookmark) },
            onDeleteBookmark = { bookmark -> viewModel.deleteBookmark(bookmark) },
            onDismiss = { viewModel.dismissBookmarks() },
        )
    }

    if (!uiState.hasBook) {
        EmptyPlayerState()
        return
    }

    val animatedBookProgress by animateFloatAsState(
        targetValue = uiState.progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 600),
        label = "book_progress",
    )

    val chapterProgress = if (uiState.currentChapterDuration > Duration.ZERO &&
                                   uiState.currentChapterPosition >= Duration.ZERO) {
        (uiState.currentChapterPosition.inWholeMilliseconds.toFloat() /
         uiState.currentChapterDuration.inWholeMilliseconds.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val animatedChapterProgress by animateFloatAsState(
        targetValue = chapterProgress,
        animationSpec = tween(durationMillis = 400),
        label = "chapter_progress",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ArchiveVoidDeep)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // ─── Source Badge ─────────────────────────────────────────────
        if (uiState.isLocalFile) {
            Surface(
                color = ArchiveInfo.copy(alpha = 0.15f),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    text = "Playing from local file",
                    style = MaterialTheme.typography.labelSmall,
                    color = ArchiveInfo,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // ─── Cover Art with Cosmic Progress Ring ────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth(0.78f)
                .aspectRatio(1f),
            contentAlignment = Alignment.Center,
        ) {
            // Cover art — inset for ring clearance
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(14.dp)
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
                inset = 10.dp,
                cornerRadius = 12.dp,
            )

            // Book progress ring — outer orbit
            ContainmentProgressRing(
                progress = animatedBookProgress,
                modifier = Modifier.matchParentSize(),
                style = RingStyle.PlayerLarge,
                progressColor = GoldFilament,
                trackColor = ArchiveOutline,
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ─── Title / Author / Chapter (no giant halo rings) ─────────
        TitleAuthorBlock(uiState)

        // A tighter chapter progress indicator (thin, readable, less circus)
        if (uiState.chapters.isNotEmpty() && uiState.currentChapterIndex >= 0) {
            Spacer(modifier = Modifier.height(10.dp))
            SigilProgressBar(
                progress = animatedChapterProgress,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                height = 4.dp,
                isActive = uiState.isPlaying,
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ─── Chapter Seek Bar (Interactive) ─────────────────────────
        if (uiState.chapters.isNotEmpty() && uiState.currentChapterIndex >= 0) {
            Column(modifier = Modifier.fillMaxWidth()) {
                val chapterLabel = buildString {
                    append("Chapter ${uiState.currentChapterIndex + 1} of ${uiState.chapters.size}")
                    uiState.currentChapterTitle?.let { title ->
                        append(": $title")
                    }
                }

                Text(
                    text = chapterLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = ArchiveTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(4.dp))

                var isChapterDragging by remember { mutableStateOf(false) }
                var chapterDragValue by remember { mutableFloatStateOf(0f) }

                Slider(
                    value = if (isChapterDragging) chapterDragValue else chapterProgress,
                    onValueChange = { value ->
                        isChapterDragging = true
                        chapterDragValue = value
                    },
                    onValueChangeFinished = {
                        viewModel.seekToChapterPosition(chapterDragValue)
                        isChapterDragging = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = ImpossibleAccent,
                        activeTrackColor = ImpossibleAccent,
                        inactiveTrackColor = ArchiveOutline.copy(alpha = 0.3f),
                    ),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = uiState.currentChapterPosition.toClockString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = ArchiveTextMuted,
                    )
                    Text(
                        text = uiState.currentChapterDuration.toClockString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = ArchiveTextMuted,
                    )
                }
            }
        } else {
            // ─── No Chapters: Total Book Seek Bar ───────────────────
            Column(modifier = Modifier.fillMaxWidth()) {
                var isDragging by remember { mutableStateOf(false) }
                var dragValue by remember { mutableFloatStateOf(0f) }

                Slider(
                    value = if (isDragging) dragValue else uiState.progress,
                    onValueChange = { value ->
                        isDragging = true
                        dragValue = value
                    },
                    onValueChangeFinished = {
                        viewModel.seekTo(dragValue)
                        isDragging = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = GoldFilament,
                        activeTrackColor = GoldFilament,
                        inactiveTrackColor = ArchiveOutline.copy(alpha = 0.3f),
                    ),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = uiState.positionText,
                        style = MaterialTheme.typography.labelSmall,
                        color = ArchiveTextMuted,
                    )
                    Text(
                        text = uiState.remainingText,
                        style = MaterialTheme.typography.labelSmall,
                        color = ArchiveTextMuted,
                    )
                }
            }
        }

        // ─── Total Book Progress (read-only text) ──────────────────
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "${uiState.positionText} / ${uiState.durationText}",
            style = MaterialTheme.typography.labelSmall,
            color = ArchiveTextMuted.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ─── Playback Controls ────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                val prevIndex = uiState.currentChapterIndex - 1
                if (prevIndex >= 0) viewModel.seekToChapter(prevIndex)
            }) {
                Icon(
                    Icons.Outlined.SkipPrevious,
                    contentDescription = "Previous Chapter",
                    tint = if (uiState.currentChapterIndex > 0) ArchiveTextSecondary else ArchiveTextMuted.copy(alpha = 0.4f),
                    modifier = Modifier.size(28.dp),
                )
            }

            IconButton(onClick = viewModel::skipBackward) {
                Icon(
                    Icons.Outlined.Replay10,
                    contentDescription = "Skip back 10s",
                    tint = ArchiveTextPrimary,
                    modifier = Modifier.size(36.dp),
                )
            }

            IconButton(
                onClick = viewModel::playPause,
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(GoldFilament),
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        color = ArchiveVoidDeep,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = if (uiState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (uiState.isPlaying) "Pause" else "Play",
                        tint = ArchiveVoidDeep,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }

            IconButton(onClick = viewModel::skipForward) {
                Icon(
                    Icons.Outlined.Forward30,
                    contentDescription = "Skip forward 30s",
                    tint = ArchiveTextPrimary,
                    modifier = Modifier.size(36.dp),
                )
            }

            IconButton(onClick = {
                val nextIndex = uiState.currentChapterIndex + 1
                if (nextIndex < uiState.chapters.size) viewModel.seekToChapter(nextIndex)
            }) {
                Icon(
                    Icons.Outlined.SkipNext,
                    contentDescription = "Next Chapter",
                    tint = if (uiState.currentChapterIndex < uiState.chapters.size - 1) ArchiveTextSecondary else ArchiveTextMuted.copy(alpha = 0.4f),
                    modifier = Modifier.size(28.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ─── Secondary Controls Row ───────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SpeedButton(
                currentSpeed = uiState.speed,
                options = viewModel.speedOptions,
                onSpeedSelected = viewModel::setSpeed,
            )

            SleepTimerButton(
                isActive = uiState.sleepTimerActive,
                timerText = uiState.sleepTimerText,
                options = viewModel.sleepTimerOptions,
                onTimerSelected = viewModel::setSleepTimer,
            )

            // Bookmark button
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { viewModel.toggleBookmarks() },
            ) {
                Icon(
                    Icons.Outlined.Bookmarks,
                    contentDescription = "Bookmarks",
                    tint = if (uiState.bookmarks.isNotEmpty()) GoldFilament else ArchiveTextSecondary,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = if (uiState.bookmarks.isNotEmpty()) "${uiState.bookmarks.size}" else "Marks",
                    style = MaterialTheme.typography.labelSmall,
                    color = ArchiveTextMuted,
                    fontSize = 10.sp,
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.VolumeUp,
                    contentDescription = "Volume",
                    tint = ArchiveTextSecondary,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = "${(uiState.volume * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = ArchiveTextMuted,
                    fontSize = 10.sp,
                )
            }
        }

        // Sleep timer countdown
        if (uiState.sleepTimerActive && uiState.sleepTimerText.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = uiState.sleepTimerText,
                style = MaterialTheme.typography.labelSmall,
                color = GoldFilament,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}

// ─── Title / Author Block ────────────────────────────────────────────────────

@Composable
private fun TitleAuthorBlock(uiState: PlayerViewModel.UiState) {
    Text(
        text = uiState.title,
        style = MaterialTheme.typography.headlineMedium,
        color = ArchiveTextPrimary,
        fontWeight = FontWeight.Bold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
    )

    Spacer(modifier = Modifier.height(4.dp))

    Text(
        text = uiState.author,
        style = MaterialTheme.typography.titleMedium,
        color = ArchiveTextSecondary,
        textAlign = TextAlign.Center,
    )

    uiState.seriesName?.let { series ->
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = series,
            style = MaterialTheme.typography.bodyMedium,
            color = GoldFilament,
            textAlign = TextAlign.Center,
        )
    }

    uiState.currentChapterTitle?.let { chapter ->
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = chapter,
            style = MaterialTheme.typography.bodyMedium,
            color = ArchiveTextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

// ─── Speed Picker Button ──────────────────────────────────────────────────

@Composable
private fun SpeedButton(
    currentSpeed: Float,
    options: List<Float>,
    onSpeedSelected: (Float) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clickable { expanded = true },
        ) {
            Icon(
                Icons.Outlined.Speed,
                contentDescription = "Speed",
                tint = ArchiveTextSecondary,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = "${currentSpeed}x",
                style = MaterialTheme.typography.labelSmall,
                color = if (currentSpeed != 1.0f) GoldFilament else ArchiveTextMuted,
                fontSize = 10.sp,
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = ArchiveVoidSurface,
        ) {
            options.forEach { speed ->
                DropdownMenuItem(
                    text = {
                        Text(
                            "${speed}x",
                            color = if (speed == currentSpeed) GoldFilament else ArchiveTextPrimary,
                            fontWeight = if (speed == currentSpeed) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        onSpeedSelected(speed)
                        expanded = false
                    },
                )
            }
        }
    }
}

// ─── Sleep Timer Button ───────────────────────────────────────────────────

@Composable
private fun SleepTimerButton(
    isActive: Boolean,
    timerText: String,
    options: List<Int?>,
    onTimerSelected: (Int?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clickable { expanded = true },
        ) {
            Icon(
                Icons.Outlined.Bedtime,
                contentDescription = "Sleep Timer",
                tint = if (isActive) GoldFilament else ArchiveTextSecondary,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = if (isActive) "On" else "Off",
                style = MaterialTheme.typography.labelSmall,
                color = if (isActive) GoldFilament else ArchiveTextMuted,
                fontSize = 10.sp,
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = ArchiveVoidSurface,
        ) {
            options.forEach { minutes ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (minutes == null) "Off" else "$minutes min",
                            color = ArchiveTextPrimary,
                        )
                    },
                    onClick = {
                        onTimerSelected(minutes)
                        expanded = false
                    },
                )
            }
        }
    }
}

// ─── Empty State — "The Silence Holds" ───────────────────────────────────

@Composable
private fun EmptyPlayerState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ArchiveVoidDeep),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.Headphones,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = ArchiveTextMuted,
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "The Silence Holds",
            style = MaterialTheme.typography.titleMedium,
            color = ArchiveTextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Choose a relic from the Archive to begin listening",
            style = MaterialTheme.typography.bodySmall,
            color = ArchiveTextMuted,
            textAlign = TextAlign.Center,
        )
    }
}

// ─── Bookmark Sheet ──────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookmarkSheet(
    bookmarks: List<Bookmark>,
    positionText: String,
    onAddBookmark: (String) -> Unit,
    onSeekToBookmark: (Bookmark) -> Unit,
    onDeleteBookmark: (Bookmark) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var newBookmarkTitle by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ArchiveVoidSurface,
        contentColor = ArchiveTextPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            // Header
            Text(
                text = "Bookmarks",
                style = MaterialTheme.typography.titleMedium,
                color = GoldFilament,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Add bookmark row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = newBookmarkTitle,
                    onValueChange = { newBookmarkTitle = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            "Bookmark at $positionText",
                            color = ArchiveTextMuted,
                        )
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = GoldFilament,
                        unfocusedBorderColor = ArchiveOutline,
                        cursorColor = GoldFilament,
                        focusedTextColor = ArchiveTextPrimary,
                        unfocusedTextColor = ArchiveTextPrimary,
                    ),
                    shape = RoundedCornerShape(10.dp),
                )

                FilledIconButton(
                    onClick = {
                        val title = newBookmarkTitle.ifBlank { "Bookmark at $positionText" }
                        onAddBookmark(title)
                        newBookmarkTitle = ""
                    },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = GoldFilament,
                        contentColor = ArchiveVoidDeep,
                    ),
                ) {
                    Icon(
                        Icons.Outlined.BookmarkAdd,
                        contentDescription = "Add bookmark",
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Bookmark list
            if (bookmarks.isEmpty()) {
                Text(
                    text = "No bookmarks yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = ArchiveTextMuted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    textAlign = TextAlign.Center,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(
                        items = bookmarks,
                        key = { "${it.libraryItemId}_${it.time}" },
                    ) { bookmark ->
                        BookmarkRow(
                            bookmark = bookmark,
                            onSeek = { onSeekToBookmark(bookmark) },
                            onDelete = { onDeleteBookmark(bookmark) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BookmarkRow(
    bookmark: Bookmark,
    onSeek: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = ArchiveVoidElevated,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSeek),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Outlined.Bookmark,
                contentDescription = null,
                tint = GoldFilament,
                modifier = Modifier.size(20.dp),
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = bookmark.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = ArchiveTextPrimary,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = bookmark.timeFormatted,
                    style = MaterialTheme.typography.labelSmall,
                    color = ArchiveTextMuted,
                    fontSize = 11.sp,
                )
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "Delete bookmark",
                    tint = ArchiveError.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
