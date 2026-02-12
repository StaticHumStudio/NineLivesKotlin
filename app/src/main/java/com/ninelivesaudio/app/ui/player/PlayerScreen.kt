package com.ninelivesaudio.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import com.ninelivesaudio.app.ui.theme.*

@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    if (!uiState.hasBook) {
        EmptyPlayerState()
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ─── Source Badge ─────────────────────────────────────────────
        if (uiState.isLocalFile) {
            Surface(
                color = CosmicInfo.copy(alpha = 0.15f),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    text = "Playing from local file",
                    style = MaterialTheme.typography.labelSmall,
                    color = CosmicInfo,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // ─── Cover Art ────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth(0.65f)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(VoidElevated),
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

        Spacer(modifier = Modifier.height(20.dp))

        // ─── Title / Author / Chapter ─────────────────────────────────
        Text(
            text = uiState.title,
            style = MaterialTheme.typography.titleLarge,
            color = Starlight,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = uiState.author,
            style = MaterialTheme.typography.bodyMedium,
            color = Mist,
            textAlign = TextAlign.Center,
        )

        uiState.seriesName?.let { series ->
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = series,
                style = MaterialTheme.typography.bodySmall,
                color = SigilGold,
                textAlign = TextAlign.Center,
            )
        }

        uiState.currentChapterTitle?.let { chapter ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = chapter,
                style = MaterialTheme.typography.bodySmall,
                color = MistFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ─── Progress Slider ──────────────────────────────────────────
        // Track drag state separately: update visual position during drag,
        // but only seek the player when the user releases the thumb.
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
                thumbColor = SigilGold,
                activeTrackColor = SigilGold,
                inactiveTrackColor = ProgressTrack,
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
                color = MistFaint,
            )
            Text(
                text = uiState.remainingText,
                style = MaterialTheme.typography.labelSmall,
                color = MistFaint,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ─── Playback Controls ────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Previous track (placeholder)
            IconButton(onClick = {}) {
                Icon(
                    Icons.Outlined.SkipPrevious,
                    contentDescription = "Previous",
                    tint = Mist,
                    modifier = Modifier.size(28.dp),
                )
            }

            // Skip backward 10s
            IconButton(onClick = viewModel::skipBackward) {
                Icon(
                    Icons.Outlined.Replay10,
                    contentDescription = "Skip back 10s",
                    tint = Starlight,
                    modifier = Modifier.size(36.dp),
                )
            }

            // Play / Pause
            IconButton(
                onClick = viewModel::playPause,
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(SigilGold),
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        color = VoidDeep,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = if (uiState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (uiState.isPlaying) "Pause" else "Play",
                        tint = VoidDeep,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }

            // Skip forward 30s
            IconButton(onClick = viewModel::skipForward) {
                Icon(
                    Icons.Outlined.Forward30,
                    contentDescription = "Skip forward 30s",
                    tint = Starlight,
                    modifier = Modifier.size(36.dp),
                )
            }

            // Next track (placeholder)
            IconButton(onClick = {}) {
                Icon(
                    Icons.Outlined.SkipNext,
                    contentDescription = "Next",
                    tint = Mist,
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
            // Speed picker
            SpeedButton(
                currentSpeed = uiState.speed,
                options = viewModel.speedOptions,
                onSpeedSelected = viewModel::setSpeed,
            )

            // Sleep timer
            SleepTimerButton(
                isActive = uiState.sleepTimerActive,
                timerText = uiState.sleepTimerText,
                options = viewModel.sleepTimerOptions,
                onTimerSelected = viewModel::setSleepTimer,
            )

            // Volume (simplified for mobile — just a label)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.VolumeUp,
                    contentDescription = "Volume",
                    tint = Mist,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = "${(uiState.volume * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MistFaint,
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
                color = SigilGold,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(modifier = Modifier.height(20.dp))
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
                tint = Mist,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = "${currentSpeed}x",
                style = MaterialTheme.typography.labelSmall,
                color = if (currentSpeed != 1.0f) SigilGold else MistFaint,
                fontSize = 10.sp,
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = VoidSurface,
        ) {
            options.forEach { speed ->
                DropdownMenuItem(
                    text = {
                        Text(
                            "${speed}x",
                            color = if (speed == currentSpeed) SigilGold else Starlight,
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
                tint = if (isActive) SigilGold else Mist,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = if (isActive) "On" else "Off",
                style = MaterialTheme.typography.labelSmall,
                color = if (isActive) SigilGold else MistFaint,
                fontSize = 10.sp,
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = VoidSurface,
        ) {
            options.forEach { minutes ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (minutes == null) "Off" else "$minutes min",
                            color = Starlight,
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

// ─── Empty State ──────────────────────────────────────────────────────────

@Composable
private fun EmptyPlayerState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(VoidDeep),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.Headphones,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MistFaint,
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "No Audiobook Playing",
            style = MaterialTheme.typography.titleMedium,
            color = Starlight,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Select a book from your Library to start listening",
            style = MaterialTheme.typography.bodySmall,
            color = MistFaint,
            textAlign = TextAlign.Center,
        )
    }
}
