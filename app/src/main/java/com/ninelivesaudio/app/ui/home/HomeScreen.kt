package com.ninelivesaudio.app.ui.home

import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.ninelivesaudio.app.R
import com.ninelivesaudio.app.ui.components.CosmicProgressRing
import com.ninelivesaudio.app.ui.components.StatusPill
import com.ninelivesaudio.app.ui.theme.*

private const val TAG = "HomeScreen"

@Composable
fun HomeScreen(
    onNavigateToLibrary: () -> Unit = {},
    onNavigateToBookDetail: (String) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    if (uiState.showEmptyState && !uiState.isLoading) {
        EmptyHomeState(onNavigateToLibrary = onNavigateToLibrary)
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // ─── Header ──────────────────────────────────────────────────────
        NineLivesHeader(
            totalListeningTime = uiState.totalListeningTimeText,
            connectionStatus = uiState.connectionStatus,
            onSecretUnlocked = { viewModel.toggleUnhingedMode() },
        )

        // ─── Nine Lives List ─────────────────────────────────────────────
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(
                items = uiState.lives,
                key = { _, item -> item.audioBookId },
            ) { _, life ->
                NineLivesCard(
                    item = life,
                    onClick = { onNavigateToBookDetail(life.audioBookId) },
                )
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  Header — Logo + Title + Total Time + Status
// ═════════════════════════════════════════════════════════════════════════════

@Composable
private fun NineLivesHeader(
    totalListeningTime: String,
    connectionStatus: com.ninelivesaudio.app.service.ConnectivityMonitor.ConnectionStatus,
    onSecretUnlocked: () -> Unit = {},
) {
    var tapCount by remember { mutableStateOf(0) }
    var lastTapTime by remember { mutableStateOf(0L) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Status pill — top right
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            StatusPill(connectionStatus = connectionStatus)
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Cosmic cat-eye logo - SECRET: Tap 9 times to unlock Unhinged Mode
        Image(
            painter = painterResource(R.drawable.nine_lives_logo),
            contentDescription = "Nine Lives Audio",
            modifier = Modifier
                .size(72.dp)
                .clickable(
                    indication = null,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                ) {
                    val currentTime = System.currentTimeMillis()
                    // Reset if more than 2 seconds between taps
                    if (currentTime - lastTapTime > 2000) {
                        tapCount = 1
                        Log.d(TAG, "NineLivesHeader: Tap counter reset (timeout). tapCount=1")
                    } else {
                        tapCount++
                        Log.d(TAG, "NineLivesHeader: Logo tapped. tapCount=$tapCount")
                    }
                    lastTapTime = currentTime

                    // Nine taps unlocks the Archive Beneath
                    if (tapCount == 9) {
                        Log.d(TAG, "NineLivesHeader: SECRET UNLOCKED! Calling onSecretUnlocked()")
                        onSecretUnlocked()
                        tapCount = 0
                    }
                },
        )

        Spacer(modifier = Modifier.height(12.dp))

        // "NINE LIVES" heading
        Text(
            text = "NINE LIVES",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = 4.sp,
            color = SigilGold,
            textAlign = TextAlign.Center,
        )

        // Total listening time
        if (totalListeningTime.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Total time given: $totalListeningTime",
                style = MaterialTheme.typography.bodySmall,
                color = Mist,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  Nine Lives Card — A single "life" entry
// ═════════════════════════════════════════════════════════════════════════════

@Composable
private fun NineLivesCard(
    item: HomeViewModel.NineLivesItem,
    onClick: () -> Unit,
) {
    val energyColor = item.cosmicEnergyColor
    val lifeColor = CosmicEnergyColors.getOrElse(item.lifeIndex) { SigilGold }

    // Animate progress ring
    val animatedProgress by animateFloatAsState(
        targetValue = (item.progressPercent / 100f).toFloat(),
        animationSpec = tween(durationMillis = 800),
        label = "progress_${item.lifeIndex}",
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (item.isMostRecent) CardBg.copy(alpha = 1.15f) else CardBg,
        tonalElevation = if (item.isMostRecent) 2.dp else 0.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, StrokeMuted),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Cover Art with Energy Border ────────────────────────────
            Box(
                modifier = Modifier.size(64.dp),
                contentAlignment = Alignment.Center,
            ) {
                // Energy border glow
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    energyColor.copy(alpha = 0.6f),
                                    energyColor.copy(alpha = 0.2f),
                                ),
                            )
                        ),
                )

                // Cover image
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(VoidElevated),
                ) {
                    if (!item.coverPath.isNullOrEmpty()) {
                        AsyncImage(
                            model = item.coverPath,
                            contentDescription = item.displayTitle,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }

                    // Download indicator dot
                    if (item.isDownloaded) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(2.dp)
                                .size(14.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(CosmicInfo.copy(alpha = 0.9f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Outlined.CloudDownload,
                                contentDescription = "Downloaded",
                                tint = Starlight,
                                modifier = Modifier.size(10.dp),
                            )
                        }
                    }
                }
            }

            // ── Info Section ────────────────────────────────────────────
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // Title
                Text(
                    text = item.displayTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Starlight,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                // Author
                Text(
                    text = item.displayAuthor,
                    style = MaterialTheme.typography.bodySmall,
                    color = Mist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 12.sp,
                )

                Spacer(modifier = Modifier.height(2.dp))

                // Life label + Weight row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Life label badge
                    Text(
                        text = item.lifeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = lifeColor,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        fontSize = 10.sp,
                    )

                    // Weight badge
                    Text(
                        text = item.weight,
                        style = MaterialTheme.typography.labelSmall,
                        color = when (item.weight) {
                            "HEAVY" -> SigilGoldDim
                            "MEDIUM" -> Mist
                            else -> MistFaint
                        },
                        fontWeight = FontWeight.Medium,
                        fontSize = 10.sp,
                    )

                    // Spacer pushes last-played to the right if there's room
                    Spacer(modifier = Modifier.weight(1f))

                    // Last played
                    if (item.lastPlayedLabel.isNotEmpty()) {
                        Text(
                            text = item.lastPlayedLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MistFaint,
                            fontSize = 10.sp,
                        )
                    }
                }
            }

            // ── Progress Ring ───────────────────────────────────────────
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                CosmicProgressRing(
                    progress = animatedProgress,
                    progressColor = lifeColor,
                    trackColor = VoidElevated,
                    modifier = Modifier.size(44.dp),
                    strokeWidth = 4.dp,
                    glowStrength = 0.55f,
                    innerShadowStrength = 0.6f,
                    showEndCapDot = true,
                )

                // Time given text inside ring
                Text(
                    text = item.timeGiven,
                    style = MaterialTheme.typography.labelSmall,
                    color = Starlight,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  Empty State — "Begin Your Journey"
// ═════════════════════════════════════════════════════════════════════════════

@Composable
private fun EmptyHomeState(
    onNavigateToLibrary: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.nine_lives_logo),
            contentDescription = null,
            modifier = Modifier.size(96.dp),
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "NINE LIVES AWAIT",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            letterSpacing = 3.sp,
            color = SigilGold,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Begin your journey",
            style = MaterialTheme.typography.titleMedium,
            color = Mist,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Start listening to track your cosmic progress",
            style = MaterialTheme.typography.bodySmall,
            color = MistFaint,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 48.dp),
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onNavigateToLibrary,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                text = "Go to Library",
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
