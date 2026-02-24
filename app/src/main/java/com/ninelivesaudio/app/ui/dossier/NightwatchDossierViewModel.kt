package com.ninelivesaudio.app.ui.dossier

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninelivesaudio.app.data.remote.ApiService
import com.ninelivesaudio.app.data.repository.AudioBookRepository
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.ListeningSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@HiltViewModel
class NightwatchDossierViewModel @Inject constructor(
    private val apiService: ApiService,
    private val audioBookRepository: AudioBookRepository,
) : ViewModel() {

    // ─── Data Models ──────────────────────────────────────────────────────

    data class BookStat(
        val bookId: String,
        val title: String,
        val author: String,
        val narrator: String?,
        val listeningTime: Duration,
        val sessionCount: Int,
        val progress: Double,
        val chaptersTotal: Int,
        val currentChapter: Int,
        val isFinished: Boolean,
        val coverUrl: String?,
        val whisper: String?,
    )

    data class NarratorStat(
        val name: String,
        val listeningTime: Duration,
        val bookCount: Int,
        val fraction: Float,
    )

    data class GenreStat(
        val name: String,
        val listeningTime: Duration,
        val bookCount: Int,
        val fraction: Float,
    )

    data class AuthorStat(
        val name: String,
        val listeningTime: Duration,
        val bookCount: Int,
        val fraction: Float,
    )

    data class DossierState(
        val isLoading: Boolean = true,
        val error: String? = null,
        val isConnected: Boolean = true,

        // Overview
        val totalListeningTime: Duration = Duration.ZERO,
        val totalSessions: Int = 0,
        val filteredNoiseSessions: Int = 0,
        val uniqueBooks: Int = 0,

        // Per-book stats
        val bookStats: List<BookStat> = emptyList(),

        // Narrator analysis
        val narratorStats: List<NarratorStat> = emptyList(),

        // Genre breakdown
        val genreStats: List<GenreStat> = emptyList(),

        // Author analysis
        val authorStats: List<AuthorStat> = emptyList(),

        // Derived stats
        val booksFinished: Int = 0,
        val dailyAverage: Duration = Duration.ZERO,
        val bestDay: String? = null,
        val bestDayTime: Duration = Duration.ZERO,

        // Temporal patterns
        val hourlyDistribution: Map<Int, Duration> = emptyMap(),
        val peakHour: Int? = null,
        val peakDayOfWeek: String? = null,

        // Contextual whispers
        val headerWhisper: String = "The Watchers Have Observed...",
        val overviewWhisper: String? = null,
        val narratorWhisper: String? = null,
        val authorWhisper: String? = null,
        val genreWhisper: String? = null,
        val temporalWhisper: String? = null,
    )

    private val _uiState = MutableStateFlow(DossierState())
    val uiState: StateFlow<DossierState> = _uiState.asStateFlow()

    // ─── Init ─────────────────────────────────────────────────────────────

    init {
        loadDossier()
    }

    fun refresh() {
        loadDossier()
    }

    private fun loadDossier() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            if (!apiService.isAuthenticated) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isConnected = false,
                        error = "Connect to your server in Settings to view the dossier.",
                    )
                }
                return@launch
            }

            try {
                val allSessions = apiService.getAllListeningSessions()
                val allBooks = audioBookRepository.getAll()
                val bookMap = allBooks.associateBy { it.id }

                // 30-day rolling window
                val cutoffMillis = System.currentTimeMillis() - 30.days.inWholeMilliseconds
                val recentSessions = allSessions.filter { it.startedAt >= cutoffMillis }

                // Sanitize session durations: cap timeListening at wall-clock span
                val sanitizedSessions = recentSessions.map { session ->
                    session.copy(timeListening = sanitizeListeningTime(session))
                }

                // Filter noise: sessions under 60 seconds
                val minThreshold = 60.seconds
                val validSessions = sanitizedSessions.filter { it.timeListening >= minThreshold }
                val noiseSessions = sanitizedSessions.size - validSessions.size

                // Aggregate
                val totalTime = validSessions.fold(Duration.ZERO) { acc, s -> acc + s.timeListening }
                val sessionsByBook = validSessions.groupBy { it.libraryItemId }

                // Per-book stats
                val bookStats = sessionsByBook.mapNotNull { (bookId, sessions) ->
                    val book = bookMap[bookId]
                    val bookTime = sessions.fold(Duration.ZERO) { acc, s -> acc + s.timeListening }
                    // Skip books with less than 2 minutes total
                    if (bookTime < 120.seconds) return@mapNotNull null

                    val title = book?.title
                        ?: sessions.firstOrNull()?.displayTitle
                        ?: "Unknown Title"

                    BookStat(
                        bookId = bookId,
                        title = title,
                        author = book?.author ?: "Unknown",
                        narrator = book?.narrator,
                        listeningTime = bookTime,
                        sessionCount = sessions.size,
                        progress = book?.progressPercent ?: 0.0,
                        chaptersTotal = book?.chapters?.size ?: 0,
                        currentChapter = (book?.getCurrentChapterIndex() ?: -1) + 1,
                        isFinished = book?.isFinished ?: false,
                        coverUrl = book?.coverPath,
                        whisper = book?.let { generateBookWhisper(it, bookTime) },
                    )
                }.sortedByDescending { it.listeningTime }

                // Narrator stats
                val narratorStats = buildNarratorStats(bookStats, totalTime)

                // Genre stats
                val genreStats = buildGenreStats(bookStats, bookMap, totalTime)

                // Author stats
                val authorStats = buildAuthorStats(bookStats)

                // Derived stats
                val booksFinished = bookStats.count { it.isFinished }
                val dailyAverage = if (totalTime > Duration.ZERO)
                    (totalTime.inWholeSeconds / 30).seconds else Duration.ZERO
                val bestDayResult = findBestSpecificDay(validSessions)

                // Temporal patterns
                val (hourly, peakHour) = buildTemporalStats(validSessions)
                val peakDay = findPeakDay(validSessions)

                // Generate contextual whispers
                val headerWhisper = generateHeaderWhisper(totalTime, validSessions.size)
                val overviewWhisper = generateOverviewWhisper(totalTime, bookStats.size)
                val narratorWhisper = generateNarratorWhisper(narratorStats)
                val authorWhisper = generateAuthorWhisper(authorStats)
                val genreWhisper = generateGenreWhisper(genreStats)
                val temporalWhisper = generateTemporalWhisper(peakHour, peakDay)

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        totalListeningTime = totalTime,
                        totalSessions = validSessions.size,
                        filteredNoiseSessions = noiseSessions,
                        uniqueBooks = bookStats.size,
                        bookStats = bookStats,
                        narratorStats = narratorStats,
                        authorStats = authorStats,
                        genreStats = genreStats,
                        booksFinished = booksFinished,
                        dailyAverage = dailyAverage,
                        bestDay = bestDayResult?.first,
                        bestDayTime = bestDayResult?.second ?: Duration.ZERO,
                        hourlyDistribution = hourly,
                        peakHour = peakHour,
                        peakDayOfWeek = peakDay,
                        headerWhisper = headerWhisper,
                        overviewWhisper = overviewWhisper,
                        narratorWhisper = narratorWhisper,
                        authorWhisper = authorWhisper,
                        genreWhisper = genreWhisper,
                        temporalWhisper = temporalWhisper,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "Failed to compile the dossier: ${e.message}",
                    )
                }
            }
        }
    }

    // ─── Session Sanitization ──────────────────────────────────────────────

    /**
     * Sanitize a session's timeListening by cross-referencing against the
     * wall-clock span (updatedAt − startedAt). If timeListening exceeds the
     * wall-clock duration (with a small tolerance), cap it.
     *
     * Fallback: if timestamps are missing or invalid, cap at 4 hours.
     */
    private fun sanitizeListeningTime(session: ListeningSession): Duration {
        val reported = session.timeListening
        val maxFallback = 4.hours

        val wallClockMs = session.updatedAt - session.startedAt
        return if (session.startedAt > 0 && session.updatedAt > session.startedAt && wallClockMs > 0) {
            // Allow 10% tolerance + 5 min buffer for clock skew / pause gaps
            val wallClock = wallClockMs.milliseconds
            val ceiling = wallClock * 1.1 + 5.minutes
            if (reported > ceiling) ceiling else reported
        } else {
            // No valid timestamps — hard cap
            if (reported > maxFallback) maxFallback else reported
        }
    }

    // ─── Aggregation Helpers ──────────────────────────────────────────────

    private fun buildNarratorStats(
        bookStats: List<BookStat>,
        totalTime: Duration,
    ): List<NarratorStat> {
        val byNarrator = mutableMapOf<String, Pair<Duration, MutableSet<String>>>()
        for (bs in bookStats) {
            val narrator = bs.narrator?.takeIf { it.isNotBlank() } ?: continue
            val (time, books) = byNarrator.getOrPut(narrator) { Duration.ZERO to mutableSetOf() }
            byNarrator[narrator] = (time + bs.listeningTime) to books.also { it.add(bs.bookId) }
        }
        val maxTime = byNarrator.values.maxOfOrNull { it.first } ?: Duration.ZERO
        return byNarrator.entries
            .map { (name, pair) ->
                NarratorStat(
                    name = name,
                    listeningTime = pair.first,
                    bookCount = pair.second.size,
                    fraction = if (maxTime > Duration.ZERO)
                        (pair.first.inWholeSeconds.toFloat() / maxTime.inWholeSeconds.toFloat())
                    else 0f,
                )
            }
            .sortedByDescending { it.listeningTime }
    }

    private fun buildGenreStats(
        bookStats: List<BookStat>,
        bookMap: Map<String, AudioBook>,
        totalTime: Duration,
    ): List<GenreStat> {
        val byGenre = mutableMapOf<String, Pair<Duration, MutableSet<String>>>()
        for (bs in bookStats) {
            val genres = bookMap[bs.bookId]?.genres ?: continue
            for (genre in genres) {
                val (time, books) = byGenre.getOrPut(genre) { Duration.ZERO to mutableSetOf() }
                byGenre[genre] = (time + bs.listeningTime) to books.also { it.add(bs.bookId) }
            }
        }
        val maxTime = byGenre.values.maxOfOrNull { it.first } ?: Duration.ZERO
        return byGenre.entries
            .map { (name, pair) ->
                GenreStat(
                    name = name,
                    listeningTime = pair.first,
                    bookCount = pair.second.size,
                    fraction = if (maxTime > Duration.ZERO)
                        (pair.first.inWholeSeconds.toFloat() / maxTime.inWholeSeconds.toFloat())
                    else 0f,
                )
            }
            .sortedByDescending { it.listeningTime }
    }

    private fun buildAuthorStats(
        bookStats: List<BookStat>,
    ): List<AuthorStat> {
        val byAuthor = mutableMapOf<String, Pair<Duration, MutableSet<String>>>()
        for (bs in bookStats) {
            val author = bs.author.takeIf { it.isNotBlank() && it != "Unknown" } ?: continue
            val (time, books) = byAuthor.getOrPut(author) { Duration.ZERO to mutableSetOf() }
            byAuthor[author] = (time + bs.listeningTime) to books.also { it.add(bs.bookId) }
        }
        val maxTime = byAuthor.values.maxOfOrNull { it.first } ?: Duration.ZERO
        return byAuthor.entries
            .map { (name, pair) ->
                AuthorStat(
                    name = name,
                    listeningTime = pair.first,
                    bookCount = pair.second.size,
                    fraction = if (maxTime > Duration.ZERO)
                        (pair.first.inWholeSeconds.toFloat() / maxTime.inWholeSeconds.toFloat())
                    else 0f,
                )
            }
            .sortedByDescending { it.listeningTime }
    }

    private fun findBestSpecificDay(
        sessions: List<ListeningSession>,
    ): Pair<String, Duration>? {
        val dayTotals = mutableMapOf<String, Duration>()
        val cal = Calendar.getInstance(TimeZone.getDefault())
        val dateFormat = SimpleDateFormat("MMM d", Locale.getDefault())

        for (session in sessions) {
            if (session.startedAt <= 0) continue
            cal.timeInMillis = session.startedAt
            val dayKey = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.DAY_OF_YEAR)}"
            dayTotals[dayKey] = (dayTotals[dayKey] ?: Duration.ZERO) + session.timeListening
        }

        val bestEntry = dayTotals.maxByOrNull { it.value } ?: return null
        val parts = bestEntry.key.split("-")
        cal.set(Calendar.YEAR, parts[0].toInt())
        cal.set(Calendar.DAY_OF_YEAR, parts[1].toInt())
        return dateFormat.format(cal.time) to bestEntry.value
    }

    private fun buildTemporalStats(
        sessions: List<ListeningSession>,
    ): Pair<Map<Int, Duration>, Int?> {
        val hourly = mutableMapOf<Int, Duration>()
        val cal = Calendar.getInstance(TimeZone.getDefault())

        for (session in sessions) {
            if (session.startedAt <= 0) continue
            cal.timeInMillis = session.startedAt
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            hourly[hour] = (hourly[hour] ?: Duration.ZERO) + session.timeListening
        }

        val peakHour = hourly.maxByOrNull { it.value }?.key
        return hourly to peakHour
    }

    private fun findPeakDay(sessions: List<ListeningSession>): String? {
        val dayTotals = mutableMapOf<Int, Duration>()
        val cal = Calendar.getInstance(TimeZone.getDefault())

        for (session in sessions) {
            if (session.startedAt <= 0) continue
            cal.timeInMillis = session.startedAt
            val dow = cal.get(Calendar.DAY_OF_WEEK)
            dayTotals[dow] = (dayTotals[dow] ?: Duration.ZERO) + session.timeListening
        }

        val peakDow = dayTotals.maxByOrNull { it.value }?.key ?: return null
        return when (peakDow) {
            Calendar.SUNDAY -> "Sunday"
            Calendar.MONDAY -> "Monday"
            Calendar.TUESDAY -> "Tuesday"
            Calendar.WEDNESDAY -> "Wednesday"
            Calendar.THURSDAY -> "Thursday"
            Calendar.FRIDAY -> "Friday"
            Calendar.SATURDAY -> "Saturday"
            else -> null
        }
    }

    // ─── Contextual Whisper Generation ────────────────────────────────────

    private fun generateBookWhisper(book: AudioBook, listenTime: Duration): String {
        val pool = when {
            book.isFinished -> BOOK_WHISPERS_FINISHED
            book.progressPercent >= 90 -> BOOK_WHISPERS_ALMOST_DONE
            listenTime >= 10.hours -> BOOK_WHISPERS_DEEP
            listenTime >= 1.hours -> BOOK_WHISPERS_INVESTED
            else -> BOOK_WHISPERS_EARLY
        }
        val seed = book.id.hashCode().toLong()
        return pool[Random(seed).nextInt(pool.size)]
    }

    private fun generateHeaderWhisper(totalTime: Duration, sessionCount: Int): String {
        return when {
            sessionCount == 0 -> "The archive is empty. Begin."
            totalTime < 1.hours -> "The watchers have begun their notes..."
            totalTime < 10.hours -> "The watchers have observed..."
            totalTime < 50.hours -> "The watchers have cataloged your habits."
            totalTime < 100.hours -> "The watchers know you by name."
            else -> "The watchers consider you permanent."
        }
    }

    private fun generateOverviewWhisper(totalTime: Duration, bookCount: Int): String? {
        return when {
            totalTime < 1.hours -> "You've barely crossed the threshold."
            totalTime < 5.hours -> "The archive is starting to take shape."
            totalTime < 20.hours -> "The shelves are adjusting to your weight."
            totalTime < 50.hours && bookCount > 5 -> "You spread your attention. The archive notices."
            totalTime < 50.hours -> "You've been here long enough to leave marks."
            totalTime < 100.hours -> "The archive considers you reliable."
            else -> "You've given it enough to matter."
        }
    }

    private fun generateNarratorWhisper(stats: List<NarratorStat>): String? {
        if (stats.isEmpty()) return null
        val top = stats.first()
        return when {
            stats.size == 1 -> "Only one voice. The archive finds this focused."
            top.fraction > 0.7f -> "${top.name}'s voice is etched into your listening."
            top.fraction > 0.4f -> "You lean toward ${top.name}. The archive has noted this."
            else -> "No single voice holds you. The archive finds this restless."
        }
    }

    private fun generateGenreWhisper(stats: List<GenreStat>): String? {
        if (stats.isEmpty()) return null
        val top = stats.first()
        return when {
            stats.size == 1 -> "${top.name}. Singular focus. The shelves approve."
            top.fraction > 0.7f -> "${top.name} is the shape of your attention."
            top.fraction > 0.4f -> "You gravitate toward ${top.name}. Predictable."
            else -> "Your tastes are scattered across the shelves."
        }
    }

    private fun generateAuthorWhisper(stats: List<AuthorStat>): String? {
        if (stats.isEmpty()) return null
        val top = stats.first()
        return when {
            stats.size == 1 -> "One author. The archive finds this devotional."
            top.fraction > 0.7f -> "${top.name} is written into your habits."
            top.fraction > 0.4f -> "You return to ${top.name}. The shelves remember."
            else -> "Your authors are many. The archive catalogs them all."
        }
    }

    private fun generateTemporalWhisper(peakHour: Int?, peakDay: String?): String? {
        if (peakHour == null) return null
        val timeDesc = when (peakHour) {
            in 0..4 -> "The small hours know you well."
            in 5..8 -> "You listen before the day begins."
            in 9..11 -> "Mornings are your archive hours."
            in 12..14 -> "Midday. The archive notes your schedule."
            in 15..17 -> "Afternoon sessions. Consistent."
            in 18..20 -> "Evenings are when you settle in."
            in 21..23 -> "The night shift. The archive expects you."
            else -> return null
        }
        return if (peakDay != null) {
            "$timeDesc ${peakDay}s especially."
        } else {
            timeDesc
        }
    }

    // ─── Whisper Pools ────────────────────────────────────────────────────

    companion object {
        private val BOOK_WHISPERS_EARLY = listOf(
            "The first pages are still settling.",
            "It hasn't noticed you yet.",
            "You're still at the threshold.",
        )
        private val BOOK_WHISPERS_INVESTED = listOf(
            "The story has adjusted to your pace.",
            "It recognizes your pauses now.",
            "You've been cataloged.",
        )
        private val BOOK_WHISPERS_DEEP = listOf(
            "You've given it hours. It has shaped around you.",
            "The narrator breathes with you now.",
            "You're not just visiting anymore.",
        )
        private val BOOK_WHISPERS_ALMOST_DONE = listOf(
            "The ending is watching. Finish it.",
            "You're stalling and it knows.",
            "The last pages are impatient.",
        )
        private val BOOK_WHISPERS_FINISHED = listOf(
            "Completed. The silence after was loud.",
            "It closed more than pages.",
            "The archive marked this as complete.",
        )
    }

    // ─── Formatting Helpers ───────────────────────────────────────────────

    fun formatDuration(duration: Duration): String {
        val totalMinutes = duration.inWholeMinutes
        return when {
            totalMinutes < 1 -> "${duration.inWholeSeconds}s"
            totalMinutes < 60 -> "${totalMinutes}m"
            else -> {
                val hours = totalMinutes / 60
                val mins = totalMinutes % 60
                if (mins == 0L) "${hours}h" else "${hours}h ${mins}m"
            }
        }
    }

    fun formatDurationLong(duration: Duration): String {
        val totalMinutes = duration.inWholeMinutes
        return when {
            totalMinutes < 1 -> "${duration.inWholeSeconds} seconds"
            totalMinutes < 60 -> "$totalMinutes minutes"
            else -> {
                val hours = totalMinutes / 60
                val mins = totalMinutes % 60
                when {
                    mins == 0L && hours == 1L -> "1 hour"
                    mins == 0L -> "$hours hours"
                    hours == 1L -> "1 hour, $mins min"
                    else -> "$hours hours, $mins min"
                }
            }
        }
    }

    fun formatHour(hour: Int): String {
        return when (hour) {
            0 -> "12a"
            in 1..11 -> "${hour}a"
            12 -> "12p"
            else -> "${hour - 12}p"
        }
    }
}
