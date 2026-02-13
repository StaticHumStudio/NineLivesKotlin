package com.ninelivesaudio.app.ui.home

import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
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
import androidx.compose.ui.res.painterResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.ninelivesaudio.app.R
import com.ninelivesaudio.app.ui.components.ContainmentFrame
import com.ninelivesaudio.app.ui.components.CornerSigils
import com.ninelivesaudio.app.ui.components.CosmicProgressRing
import com.ninelivesaudio.app.ui.components.StatusPill
import com.ninelivesaudio.app.ui.animation.unhinged.anomalies.AnomalyHost
import com.ninelivesaudio.app.ui.animation.unhinged.anomalies.AnomalyTriggerContext
import com.ninelivesaudio.app.ui.copy.unhinged.CopyEngine
import com.ninelivesaudio.app.ui.copy.unhinged.CopyStyleGuide
import com.ninelivesaudio.app.ui.theme.unhinged.*

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

    AnomalyHost(
        currentContext = AnomalyTriggerContext.HOME,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(ArchiveVoidDeep),
        ) {
            // ─── Header ──────────────────────────────────────────────────────
            NineLivesHeader(
                totalListeningTime = uiState.totalListeningTimeText,
                connectionStatus = uiState.connectionStatus,
                onSecretUnlocked = { viewModel.triggerVaultEasterEgg() },
            )

            // ─── Nine Lives 3×3 Vault Grid ─────────────────────────────────
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp),
                contentPadding = PaddingValues(bottom = 80.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                userScrollEnabled = false,
            ) {
                itemsIndexed(
                    items = uiState.lives,
                    key = { _, item -> item.audioBookId },
                ) { index, life ->
                    HomeGridTile(
                        item = life,
                        index = index,
                        onClick = { onNavigateToBookDetail(life.audioBookId) },
                    )
                }
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  3×3 Grid Tile — Cover + ContainmentFrame + CosmicProgressRing + Sigils
// ═════════════════════════════════════════════════════════════════════════════

@Composable
private fun HomeGridTile(
    item: HomeViewModel.NineLivesItem,
    index: Int,
    onClick: () -> Unit,
) {
    val animatedProgress by animateFloatAsState(
        targetValue = (item.progressPercent / 100f).toFloat(),
        animationSpec = tween(durationMillis = 800),
        label = "progress_$index",
    )

    // Deterministic misalignment: the center tile (index 4) gets a subtle offset
    val misalignOffset = if (index == 4) IntOffset(2, -2) else IntOffset.Zero

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .offset { misalignOffset }
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Cover art (fills the tile with inner padding for ring clearance)
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(6.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(ArchiveVoidElevated),
        ) {
            if (!item.coverPath.isNullOrEmpty()) {
                AsyncImage(
                    model = item.coverPath,
                    contentDescription = item.displayTitle,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }

        // Containment frame overlay
        ContainmentFrame(
            modifier = Modifier.matchParentSize(),
            inset = 2.dp,
            cornerRadius = 14.dp,
        )

        // Progress ring overlay
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
            downloaded = item.isDownloaded,
            bookmarked = item.isBookmarked,
            modifier = Modifier.matchParentSize(),
        )

        // Life label at bottom
        Text(
            text = item.lifeLabel,
            style = MaterialTheme.typography.labelSmall,
            color = GoldFilament,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            letterSpacing = 1.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp),
        )
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

        // Cosmic cat-eye logo — tap 9 times for vault acknowledgment
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
                    if (currentTime - lastTapTime > 2000) {
                        tapCount = 1
                    } else {
                        tapCount++
                    }
                    lastTapTime = currentTime
                    if (tapCount == 9) {
                        Log.d(TAG, "THE VAULT ACKNOWLEDGES YOU")
                        onSecretUnlocked()
                        tapCount = 0
                    }
                },
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "NINE LIVES",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = 4.sp,
            color = GoldFilament,
            textAlign = TextAlign.Center,
        )

        val homeSubtitle = CopyEngine.getSubtitle(
            CopyStyleGuide.Home.HOME_NAV_RITUAL,
            CopyStyleGuide.Home.HOME_NAV_UNHINGED,
        )
        if (homeSubtitle != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = homeSubtitle,
                style = MaterialTheme.typography.labelSmall,
                color = ArchiveTextMuted,
                textAlign = TextAlign.Center,
                fontSize = 10.sp,
            )
        }

        if (totalListeningTime.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Total time given: $totalListeningTime",
                style = MaterialTheme.typography.bodySmall,
                color = ArchiveTextSecondary,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  Empty State — "The Archive Awaits"
// ═════════════════════════════════════════════════════════════════════════════

@Composable
private fun EmptyHomeState(
    onNavigateToLibrary: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ArchiveVoidDeep),
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
            color = GoldFilament,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "The Archive stands empty",
            style = MaterialTheme.typography.titleMedium,
            color = ArchiveTextSecondary,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = CopyEngine.getEmptyStateFlavor(
                CopyStyleGuide.EmptyStates.EMPTY_LIBRARY_RITUAL,
                CopyStyleGuide.EmptyStates.EMPTY_LIBRARY_UNHINGED,
            ) ?: "Begin listening to fill these halls with your progress",
            style = MaterialTheme.typography.bodySmall,
            color = ArchiveTextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 48.dp),
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onNavigateToLibrary,
            colors = ButtonDefaults.buttonColors(
                containerColor = GoldFilament,
                contentColor = ArchiveVoidDeep,
            ),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                text = "Enter The Archive",
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
