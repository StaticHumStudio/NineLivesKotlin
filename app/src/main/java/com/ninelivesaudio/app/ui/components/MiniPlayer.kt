package com.ninelivesaudio.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.ninelivesaudio.app.service.PlaybackManager
import com.ninelivesaudio.app.service.PlaybackState
import com.ninelivesaudio.app.ui.theme.*

/**
 * Persistent mini player bar at the bottom of all screens.
 * Shows cover thumbnail, title, play/pause button, and progress bar.
 * Tapping navigates to the full Player screen.
 */
@Composable
fun MiniPlayer(
    playbackManager: PlaybackManager,
    onNavigateToPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentBook by playbackManager.currentBook.collectAsStateWithLifecycle()
    val playbackState by playbackManager.playbackState.collectAsStateWithLifecycle()
    val position by playbackManager.position.collectAsStateWithLifecycle()
    val duration by playbackManager.duration.collectAsStateWithLifecycle()
    val currentChapter by playbackManager.currentChapter.collectAsStateWithLifecycle()

    // Only show if a book is loaded
    val book = currentBook ?: return
    if (playbackState == PlaybackState.STOPPED && book.title.isEmpty()) return

    val progress = if (duration > kotlin.time.Duration.ZERO) {
        (position.inWholeMilliseconds.toFloat() / duration.inWholeMilliseconds.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(VoidSurface),
    ) {
        // Progress bar at top
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp),
            color = SigilGold,
            trackColor = ProgressTrack,
        )

        // Content row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onNavigateToPlayer)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Cover thumbnail
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(VoidElevated),
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

            // Title + chapter
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = Starlight,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 13.sp,
                )
                currentChapter?.let { ch ->
                    Text(
                        text = ch.title,
                        style = MaterialTheme.typography.labelSmall,
                        color = MistFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 11.sp,
                    )
                }
            }

            // Play / Pause button
            IconButton(
                onClick = {
                    if (playbackState == PlaybackState.PLAYING) {
                        playbackManager.pause()
                    } else {
                        playbackManager.play()
                    }
                },
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(SigilGold),
            ) {
                Icon(
                    imageVector = if (playbackState == PlaybackState.PLAYING) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playbackState == PlaybackState.PLAYING) "Pause" else "Play",
                    tint = VoidDeep,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}
