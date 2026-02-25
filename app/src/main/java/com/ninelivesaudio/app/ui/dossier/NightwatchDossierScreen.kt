package com.ninelivesaudio.app.ui.dossier

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.request.ImageRequest
import com.ninelivesaudio.app.ui.components.BookCoverImage
import com.ninelivesaudio.app.R
import com.ninelivesaudio.app.ui.dossier.NightwatchDossierViewModel.*
import com.ninelivesaudio.app.ui.theme.unhinged.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.time.Duration

@Composable
fun NightwatchDossierScreen(
    onNavigateBack: () -> Unit,
    viewModel: NightwatchDossierViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ArchiveVoidDeep)
    ) {
        // ─── Header ────────────────────────────────────────────────────
        DossierHeader(
            headerWhisper = state.headerWhisper,
            onNavigateBack = onNavigateBack,
        )

        // ─── Content ───────────────────────────────────────────────────
        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = GoldFilament,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(32.dp),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Compiling the dossier...",
                            style = MaterialTheme.typography.bodySmall,
                            color = ArchiveTextMuted,
                        )
                    }
                }
            }

            state.error != null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = state.error ?: "An error occurred.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ArchiveTextSecondary,
                    )
                }
            }

            else -> {
                DossierContent(state, viewModel)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  Header
// ═══════════════════════════════════════════════════════════════

@Composable
private fun DossierHeader(
    headerWhisper: String,
    onNavigateBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ArchiveVoidDeep)
            .padding(start = 4.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onNavigateBack) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "Back",
                tint = ArchiveTextSecondary,
            )
        }
        Column {
            Text(
                text = "Nightwatch Dossier",
                style = MaterialTheme.typography.headlineMedium,
                color = ArchiveTextPrimary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = headerWhisper,
                style = MaterialTheme.typography.bodySmall,
                color = ArchiveTextFlavor,
                fontStyle = FontStyle.Italic,
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  Content
// ═══════════════════════════════════════════════════════════════

@Composable
private fun DossierContent(
    state: DossierState,
    viewModel: NightwatchDossierViewModel,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ─── Period Selector ─────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DossierPeriod.entries.forEach { period ->
                FilterChip(
                    selected = state.selectedPeriod == period,
                    onClick = { viewModel.onPeriodChanged(period) },
                    label = {
                        Text(
                            text = period.label,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = ArchiveVoidSurface,
                        selectedContainerColor = GoldFilamentFaint,
                        labelColor = ArchiveTextSecondary,
                        selectedLabelColor = GoldFilament,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        borderColor = ArchiveOutline,
                        selectedBorderColor = GoldFilament.copy(alpha = 0.4f),
                        enabled = true,
                        selected = state.selectedPeriod == period,
                    ),
                    shape = RoundedCornerShape(20.dp),
                )
            }
        }

        // ─── Overview ──────────────────────────────────────────────
        OverviewSection(state, viewModel)

        // ─── Book Stats ────────────────────────────────────────────
        if (state.bookStats.isNotEmpty()) {
            BookStatsSection(state.bookStats, viewModel)
        }

        // ─── Narrators ─────────────────────────────────────────────
        if (state.narratorStats.isNotEmpty()) {
            NarratorSection(state.narratorStats, state.narratorWhisper, viewModel)
        }

        // ─── Authors ───────────────────────────────────────────────
        if (state.authorStats.isNotEmpty()) {
            AuthorSection(state.authorStats, state.authorWhisper, viewModel)
        }

        // ─── Genres ────────────────────────────────────────────────
        if (state.genreStats.isNotEmpty()) {
            GenreSection(state.genreStats, state.genreWhisper, viewModel)
        }

        // ─── Temporal ──────────────────────────────────────────────
        if (state.hourlyDistribution.isNotEmpty()) {
            TemporalSection(state, viewModel)
        }

        // ─── Shareable Summary Card ────────────────────────────────
        DossierShareSection(state, viewModel)

        // Bottom padding
        Spacer(modifier = Modifier.height(80.dp))
    }
}

// ═══════════════════════════════════════════════════════════════
//  Overview Section
// ═══════════════════════════════════════════════════════════════

@Composable
private fun OverviewSection(
    state: DossierState,
    viewModel: NightwatchDossierViewModel,
) {
    DossierCard {
        // Big number: total listening time
        Text(
            text = viewModel.formatDurationLong(state.totalListeningTime),
            style = MaterialTheme.typography.headlineLarge,
            color = GoldFilament,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = state.selectedPeriod.overviewSubtitle(),
            style = MaterialTheme.typography.bodySmall,
            color = ArchiveTextMuted,
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Stats row 1
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            StatChip(value = state.totalSessions.toString(), label = "sessions")
            StatChip(value = state.uniqueBooks.toString(), label = "books")
            StatChip(value = state.booksFinished.toString(), label = "finished")
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Stats row 2
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            StatChip(
                value = viewModel.formatDuration(state.dailyAverage),
                label = "daily avg",
            )
            state.bestDay?.let { day ->
                StatChip(
                    value = day,
                    label = viewModel.formatDuration(state.bestDayTime),
                    highlight = true,
                )
            }
        }

        // Noise filter notice
        if (state.filteredNoiseSessions > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${state.filteredNoiseSessions} brief sessions excluded",
                style = MaterialTheme.typography.labelSmall,
                color = ArchiveTextMuted,
                fontSize = 10.sp,
            )
        }

        // Contextual whisper
        state.overviewWhisper?.let { whisper ->
            Spacer(modifier = Modifier.height(8.dp))
            WhisperText(whisper)
        }
    }
}

@Composable
private fun StatChip(value: String, label: String, highlight: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = if (highlight) GoldFilament else ArchiveTextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = ArchiveTextMuted,
        )
    }
}

// ═══════════════════════════════════════════════════════════════
//  Book Stats Section
// ═══════════════════════════════════════════════════════════════

@Composable
private fun BookStatsSection(
    bookStats: List<BookStat>,
    viewModel: NightwatchDossierViewModel,
) {
    SectionHeader("Books")

    var expanded by remember { mutableStateOf(false) }
    val visibleBooks = if (expanded || bookStats.size <= 5) bookStats else bookStats.take(5)

    visibleBooks.forEach { book ->
        DossierCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Cover thumbnail
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(ArchiveVoidElevated),
                ) {
                    BookCoverImage(
                        coverUrl = book.coverUrl,
                        contentDescription = book.title,
                        modifier = Modifier.fillMaxSize(),
                        title = book.title,
                        bookId = book.bookId,
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = book.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ArchiveTextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = book.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = ArchiveTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    book.narrator?.let { narrator ->
                        Text(
                            text = "Read by $narrator",
                            style = MaterialTheme.typography.labelSmall,
                            color = ArchiveTextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // Time badge
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = viewModel.formatDuration(book.listeningTime),
                        style = MaterialTheme.typography.titleMedium,
                        color = GoldFilament,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "${book.sessionCount} sessions",
                        style = MaterialTheme.typography.labelSmall,
                        color = ArchiveTextMuted,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Progress bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LinearProgressIndicator(
                    progress = { (book.progress / 100.0).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = if (book.isFinished) ArchiveSuccess else GoldFilament,
                    trackColor = ArchiveVoidElevated,
                )

                val progressLabel = when {
                    book.isFinished -> "Complete"
                    book.chaptersTotal > 0 && book.currentChapter > 0 ->
                        "Ch ${book.currentChapter}/${book.chaptersTotal}"
                    book.progress > 0 -> "${book.progress.toInt()}%"
                    else -> ""
                }
                if (progressLabel.isNotEmpty()) {
                    Text(
                        text = progressLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (book.isFinished) ArchiveSuccess else ArchiveTextMuted,
                        fontSize = 10.sp,
                    )
                }
            }

            // Book whisper
            book.whisper?.let { whisper ->
                Spacer(modifier = Modifier.height(4.dp))
                WhisperText(whisper)
            }
        }
    }

    if (bookStats.size > 5) {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = if (expanded) "Show less" else "Show all ${bookStats.size} books",
                color = GoldFilamentDim,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  Narrator Section
// ═══════════════════════════════════════════════════════════════

@Composable
private fun NarratorSection(
    stats: List<NarratorStat>,
    whisper: String?,
    viewModel: NightwatchDossierViewModel,
) {
    SectionHeader("Voices")

    DossierCard {
        stats.take(5).forEach { narrator ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = narrator.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = ArchiveTextPrimary,
                    modifier = Modifier
                        .weight(0.45f)
                        .widthIn(min = 100.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                LinearProgressIndicator(
                    progress = { narrator.fraction.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = GoldFilament,
                    trackColor = ArchiveVoidElevated,
                )

                Text(
                    text = viewModel.formatDuration(narrator.listeningTime),
                    style = MaterialTheme.typography.labelSmall,
                    color = ArchiveTextSecondary,
                    modifier = Modifier.width(40.dp),
                )
            }
        }

        whisper?.let {
            Spacer(modifier = Modifier.height(8.dp))
            WhisperText(it)
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  Genre Section
// ═══════════════════════════════════════════════════════════════

@Composable
private fun GenreSection(
    stats: List<GenreStat>,
    whisper: String?,
    viewModel: NightwatchDossierViewModel,
) {
    SectionHeader("Genres")

    DossierCard {
        stats.take(6).forEach { genre ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = genre.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = ArchiveTextPrimary,
                    modifier = Modifier
                        .weight(0.45f)
                        .widthIn(min = 100.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                LinearProgressIndicator(
                    progress = { genre.fraction.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = ImpossibleAccent,
                    trackColor = ArchiveVoidElevated,
                )

                Text(
                    text = viewModel.formatDuration(genre.listeningTime),
                    style = MaterialTheme.typography.labelSmall,
                    color = ArchiveTextSecondary,
                    modifier = Modifier.width(40.dp),
                )
            }
        }

        whisper?.let {
            Spacer(modifier = Modifier.height(8.dp))
            WhisperText(it)
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  Author Section
// ═══════════════════════════════════════════════════════════════

@Composable
private fun AuthorSection(
    stats: List<AuthorStat>,
    whisper: String?,
    viewModel: NightwatchDossierViewModel,
) {
    SectionHeader("Authors")

    DossierCard {
        stats.take(5).forEach { author ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = author.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = ArchiveTextPrimary,
                    modifier = Modifier
                        .weight(0.45f)
                        .widthIn(min = 100.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                LinearProgressIndicator(
                    progress = { author.fraction.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = ImpossibleAccent,
                    trackColor = ArchiveVoidElevated,
                )

                Text(
                    text = viewModel.formatDuration(author.listeningTime),
                    style = MaterialTheme.typography.labelSmall,
                    color = ArchiveTextSecondary,
                    modifier = Modifier.width(40.dp),
                )
            }
        }

        whisper?.let {
            Spacer(modifier = Modifier.height(8.dp))
            WhisperText(it)
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  Temporal Section
// ═══════════════════════════════════════════════════════════════

@Composable
private fun TemporalSection(
    state: DossierState,
    viewModel: NightwatchDossierViewModel,
) {
    SectionHeader("Temporal Patterns")

    DossierCard {
        // Hour distribution as a simple bar chart
        Text(
            text = "Listening by hour",
            style = MaterialTheme.typography.labelMedium,
            color = ArchiveTextSecondary,
        )
        Spacer(modifier = Modifier.height(8.dp))

        val maxDuration = state.hourlyDistribution.values.maxOrNull() ?: Duration.ZERO
        val hours = (0..23).toList()
        // Show condensed: group into 4-hour blocks
        val blocks = listOf(
            "12a–4a" to (0..3),
            "4a–8a" to (4..7),
            "8a–12p" to (8..11),
            "12p–4p" to (12..15),
            "4p–8p" to (16..19),
            "8p–12a" to (20..23),
        )

        blocks.forEach { (label, range) ->
            val blockTime = range.fold(Duration.ZERO) { acc, h ->
                acc + (state.hourlyDistribution[h] ?: Duration.ZERO)
            }
            if (blockTime > Duration.ZERO || range.contains(state.peakHour)) {
                val blockMax = blocks.maxOf { (_, r) ->
                    r.fold(Duration.ZERO) { acc, h ->
                        acc + (state.hourlyDistribution[h] ?: Duration.ZERO)
                    }
                }
                val fraction = if (blockMax > Duration.ZERO)
                    blockTime.inWholeSeconds.toFloat() / blockMax.inWholeSeconds.toFloat()
                else 0f
                val isPeak = range.contains(state.peakHour)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isPeak) GoldFilament else ArchiveTextMuted,
                        modifier = Modifier.width(56.dp),
                        fontSize = 10.sp,
                    )

                    LinearProgressIndicator(
                        progress = { fraction.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .weight(1f)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = if (isPeak) GoldFilament else GoldFilamentDim,
                        trackColor = ArchiveVoidElevated,
                    )

                    Text(
                        text = viewModel.formatDuration(blockTime),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isPeak) GoldFilament else ArchiveTextSecondary,
                        modifier = Modifier.width(36.dp),
                        fontSize = 10.sp,
                    )
                }
            }
        }

        // Peak day of week
        state.peakDayOfWeek?.let { day ->
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Most active day",
                    style = MaterialTheme.typography.labelSmall,
                    color = ArchiveTextMuted,
                )
                Text(
                    text = day,
                    style = MaterialTheme.typography.labelSmall,
                    color = GoldFilament,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        // Best single day
        state.bestDay?.let { day ->
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Best single day",
                    style = MaterialTheme.typography.labelSmall,
                    color = ArchiveTextMuted,
                )
                Text(
                    text = "$day — ${viewModel.formatDuration(state.bestDayTime)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = GoldFilament,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        state.temporalWhisper?.let {
            Spacer(modifier = Modifier.height(8.dp))
            WhisperText(it)
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  Shareable Summary Card
// ═══════════════════════════════════════════════════════════════

@Composable
private fun DossierShareSection(
    state: DossierState,
    viewModel: NightwatchDossierViewModel,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rootView = LocalView.current
    var cardBounds by remember { mutableStateOf<android.graphics.Rect?>(null) }
    var isSharing by remember { mutableStateOf(false) }

    SectionHeader("Share")

    // The card that will be captured directly from the Compose host view.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInRoot()
                cardBounds = android.graphics.Rect(
                    bounds.left.toInt(),
                    bounds.top.toInt(),
                    bounds.right.toInt(),
                    bounds.bottom.toInt(),
                )
            },
    ) {
        DossierSummaryCard(
            state = state,
            viewModel = viewModel,
        )
    }

    Spacer(modifier = Modifier.height(8.dp))

    // Share button
    Button(
        onClick = {
            val bounds = cardBounds
            if (isSharing || bounds == null || bounds.isEmpty) return@Button
            isSharing = true
            scope.launch {
                try {
                    val bitmap = captureViewRegion(rootView, bounds)
                    if (bitmap != null) {
                        shareBitmap(context, bitmap)
                    } else {
                        Toast.makeText(context, "Capture failed – please try again", Toast.LENGTH_SHORT)
                            .show()
                    }
                } catch (e: Exception) {
                    Log.e("DossierShare", "Share failed", e)
                    Toast.makeText(context, "Share failed – please try again", Toast.LENGTH_SHORT)
                        .show()
                } finally {
                    isSharing = false
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = !isSharing,
        colors = ButtonDefaults.buttonColors(
            containerColor = GoldFilamentFaint,
            contentColor = GoldFilament,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        if (isSharing) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = GoldFilament,
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                Icons.Outlined.Share,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(if (isSharing) "Preparing..." else "Share Dossier Summary")
    }
}

@Composable
private fun DossierSummaryCard(
    state: DossierState,
    viewModel: NightwatchDossierViewModel,
) {
    val context = LocalContext.current

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = ArchiveVoidDeep,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ─── Brand Header ───────────────────────────────────────
            Text(
                text = "NIGHTWATCH DOSSIER",
                style = MaterialTheme.typography.labelMedium,
                color = GoldFilament,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = state.selectedPeriod.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = ArchiveTextMuted,
                letterSpacing = 1.sp,
            )

            Spacer(modifier = Modifier.height(20.dp))

            // ─── Hero Stat (bordered frame) ─────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(ArchiveVoidSurface)
                    .border(
                        width = 1.dp,
                        color = GoldFilament.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(12.dp),
                    )
                    .padding(vertical = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = viewModel.formatDurationLong(state.totalListeningTime),
                        style = MaterialTheme.typography.headlineLarge,
                        color = GoldFilament,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        fontSize = 30.sp,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "TOTAL LISTENING",
                        style = MaterialTheme.typography.labelSmall,
                        color = ArchiveTextMuted,
                        letterSpacing = 2.sp,
                        fontSize = 10.sp,
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ─── Cover Podium (top 3 books, center tallest) ─────────
            if (state.bookStats.isNotEmpty()) {
                val topBooks = state.bookStats.take(3)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    // #2 — left (smaller)
                    if (topBooks.size >= 2) {
                        ShareCoverItem(
                            book = topBooks[1],
                            width = 64.dp,
                            height = 88.dp,
                            rank = 2,
                            context = context,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    // #1 — center (tallest)
                    ShareCoverItem(
                        book = topBooks[0],
                        width = 80.dp,
                        height = 110.dp,
                        rank = 1,
                        context = context,
                    )

                    // #3 — right (smallest)
                    if (topBooks.size >= 3) {
                        Spacer(modifier = Modifier.width(8.dp))
                        ShareCoverItem(
                            book = topBooks[2],
                            width = 58.dp,
                            height = 80.dp,
                            rank = 3,
                            context = context,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
            }

            // ─── Stat Pills Row ─────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ShareStatPill(state.totalSessions.toString(), "sessions")
                ShareStatPill(state.uniqueBooks.toString(), "books")
                ShareStatPill(state.booksFinished.toString(), "finished")
                ShareStatPill(
                    viewModel.formatDuration(state.dailyAverage),
                    "daily avg",
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ─── Top Picks Leaderboard ──────────────────────────────
            val topAuthor = state.authorStats.firstOrNull()
            val topNarrator = state.narratorStats.firstOrNull()
            val topGenre = state.genreStats.firstOrNull()

            if (topAuthor != null || topNarrator != null || topGenre != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(ArchiveVoidSurface)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    topAuthor?.let {
                        ShareLeaderboardRow(
                            label = "TOP AUTHOR",
                            value = it.name,
                            fraction = it.fraction,
                            color = GoldFilament,
                        )
                    }
                    topNarrator?.let {
                        ShareLeaderboardRow(
                            label = "TOP VOICE",
                            value = it.name,
                            fraction = it.fraction,
                            color = ImpossibleAccent,
                        )
                    }
                    topGenre?.let {
                        ShareLeaderboardRow(
                            label = "TOP GENRE",
                            value = it.name,
                            fraction = it.fraction,
                            color = GoldFilamentDim,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            // ─── Temporal Callout ───────────────────────────────────
            val temporalParts = buildList {
                state.peakDayOfWeek?.let { add("Most active: $it") }
                state.bestDay?.let { day ->
                    add("Best day: $day")
                }
            }
            if (temporalParts.isNotEmpty()) {
                Text(
                    text = temporalParts.joinToString("  ·  "),
                    style = MaterialTheme.typography.labelSmall,
                    color = ArchiveTextTertiary,
                    textAlign = TextAlign.Center,
                    letterSpacing = 0.5.sp,
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // ─── Whisper Divider ────────────────────────────────────
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 32.dp),
                color = GoldFilament.copy(alpha = 0.15f),
                thickness = 1.dp,
            )

            Spacer(modifier = Modifier.height(14.dp))

            // ─── Whisper ────────────────────────────────────────────
            Text(
                text = "\"${state.headerWhisper}\"",
                style = MaterialTheme.typography.bodySmall,
                color = ArchiveTextFlavor,
                fontStyle = FontStyle.Italic,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 8.dp),
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ─── Branding Footer ────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Image(
                    painter = painterResource(R.mipmap.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    contentScale = ContentScale.Crop,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Nine Lives Audio",
                    style = MaterialTheme.typography.labelSmall,
                    color = ArchiveTextMuted,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                )
                Text(
                    text = "  ·  statichum.studio",
                    style = MaterialTheme.typography.labelSmall,
                    color = ArchiveTextMuted.copy(alpha = 0.6f),
                )
            }
        }
    }
}

// ─── Share Card Sub-Components ───────────────────────────────────────────

@Composable
private fun ShareCoverItem(
    book: NightwatchDossierViewModel.BookStat,
    width: Dp,
    height: Dp,
    rank: Int,
    context: android.content.Context,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(width = width, height = height)
                .clip(RoundedCornerShape(8.dp))
                .background(ArchiveVoidElevated)
                .then(
                    if (rank == 1) Modifier.border(
                        width = 1.dp,
                        color = GoldFilament.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(8.dp),
                    ) else Modifier
                ),
        ) {
            BookCoverImage(
                coverUrl = book.coverUrl,
                contentDescription = book.title,
                modifier = Modifier.fillMaxSize(),
                title = book.title,
                bookId = book.bookId,
                imageModel = if (!book.coverUrl.isNullOrEmpty()) {
                    ImageRequest.Builder(context)
                        .data(book.coverUrl)
                        .allowHardware(false)
                        .build()
                } else null,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = book.title,
            style = MaterialTheme.typography.labelSmall,
            color = if (rank == 1) ArchiveTextPrimary else ArchiveTextMuted,
            fontWeight = if (rank == 1) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(width),
        )
    }
}

@Composable
private fun ShareStatPill(
    value: String,
    label: String,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = ArchiveTextPrimary,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = ArchiveTextMuted,
            fontSize = 8.sp,
            letterSpacing = 1.sp,
        )
    }
}

@Composable
private fun ShareLeaderboardRow(
    label: String,
    value: String,
    fraction: Float,
    color: Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = ArchiveTextMuted,
                letterSpacing = 1.5.sp,
                fontSize = 9.sp,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = ArchiveTextPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).padding(start = 8.dp),
            )
        }
        // Progress bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(ArchiveVoidElevated),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction.coerceIn(0.05f, 1f))
                    .clip(RoundedCornerShape(2.dp))
                    .background(color),
            )
        }
    }
}

/**
 * Captures a region of the view's window using [PixelCopy], which reads
 * directly from the GPU surface and therefore supports hardware bitmaps
 * (Coil images, RenderEffects, etc.) without the
 * "Software rendering doesn't support hardware bitmaps" crash.
 *
 * Min SDK 30 guarantees [PixelCopy] is always available.
 */
private suspend fun captureViewRegion(
    view: View,
    bounds: android.graphics.Rect,
): Bitmap? {
    val window = view.context.let {
        (it as? android.app.Activity)?.window
    } ?: run {
        Log.e("DossierShare", "Cannot obtain window for PixelCopy")
        return null
    }

    // Map view-local bounds to window (screen) coordinates
    val locationInWindow = IntArray(2)
    view.getLocationInWindow(locationInWindow)

    val srcRect = android.graphics.Rect(
        (bounds.left + locationInWindow[0]).coerceAtLeast(0),
        (bounds.top + locationInWindow[1]).coerceAtLeast(0),
        (bounds.right + locationInWindow[0]),
        (bounds.bottom + locationInWindow[1]),
    )
    if (srcRect.isEmpty) return null

    val bitmap = Bitmap.createBitmap(srcRect.width(), srcRect.height(), Bitmap.Config.ARGB_8888)

    return suspendCancellableCoroutine { cont ->
        PixelCopy.request(
            window,
            srcRect,
            bitmap,
            { result ->
                if (result == PixelCopy.SUCCESS) {
                    cont.resumeWith(Result.success(bitmap))
                } else {
                    Log.e("DossierShare", "PixelCopy failed with result code $result")
                    bitmap.recycle()
                    cont.resumeWith(Result.success(null))
                }
            },
            Handler(Looper.getMainLooper()),
        )
    }
}

private suspend fun shareBitmap(
    context: android.content.Context,
    bitmap: Bitmap,
) {
    // Save image on IO thread
    val uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "shared_images")
        dir.mkdirs()
        val file = File(dir, "nightwatch_dossier.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }

        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    }

    // Launch share chooser on main thread
    withContext(Dispatchers.Main) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("Nightwatch Dossier", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.packageManager.queryIntentActivities(intent, 0).forEach { resolveInfo ->
            context.grantUriPermission(
                resolveInfo.activityInfo.packageName,
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        context.startActivity(Intent.createChooser(intent, "Share Dossier"))
    }
}

// ═══════════════════════════════════════════════════════════════
//  Shared Components
// ═══════════════════════════════════════════════════════════════

@Composable
private fun DossierCard(
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = ArchiveVoidSurface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            content = content,
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = GoldFilament,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun WhisperText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = ArchiveTextFlavor,
        fontStyle = FontStyle.Italic,
        lineHeight = 16.sp,
    )
}
