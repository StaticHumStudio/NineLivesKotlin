package com.ninelivesaudio.app.ui.dossier

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.ninelivesaudio.app.ui.dossier.NightwatchDossierViewModel.*
import com.ninelivesaudio.app.ui.theme.unhinged.*
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
                Icons.Outlined.ArrowBack,
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

        // ─── Genres ────────────────────────────────────────────────
        if (state.genreStats.isNotEmpty()) {
            GenreSection(state.genreStats, state.genreWhisper, viewModel)
        }

        // ─── Temporal ──────────────────────────────────────────────
        if (state.hourlyDistribution.isNotEmpty()) {
            TemporalSection(state, viewModel)
        }

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
            text = "in the archive",
            style = MaterialTheme.typography.bodySmall,
            color = ArchiveTextMuted,
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Stats row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            StatChip(value = state.totalSessions.toString(), label = "sessions")
            StatChip(value = state.uniqueBooks.toString(), label = "books")
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
private fun StatChip(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = ArchiveTextPrimary,
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

    bookStats.forEach { book ->
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
                    if (!book.coverUrl.isNullOrEmpty()) {
                        AsyncImage(
                            model = book.coverUrl,
                            contentDescription = book.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
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
                    modifier = Modifier.width(100.dp),
                    maxLines = 1,
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
                    modifier = Modifier.width(100.dp),
                    maxLines = 1,
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

        // Peak day
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

        state.temporalWhisper?.let {
            Spacer(modifier = Modifier.height(8.dp))
            WhisperText(it)
        }
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
