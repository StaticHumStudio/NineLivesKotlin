package com.ninelivesaudio.app.data.remote

import android.net.Uri
import com.ninelivesaudio.app.data.remote.dto.*
import com.ninelivesaudio.app.domain.model.*
import com.ninelivesaudio.app.service.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * High-level API service that wraps Retrofit calls with error handling and
 * maps API DTOs to domain models. Ports the C# AudioBookshelfApiService logic.
 */
@Singleton
class ApiService @Inject constructor(
    private val api: AudiobookshelfApi,
    private val authInterceptor: AuthInterceptor,
    private val settingsManager: SettingsManager,
) {
    companion object {
        private const val TOKEN_VALIDATION_DEBOUNCE_MS = 15_000L
    }

    var lastError: String? = null
        private set

    private val tokenValidationMutex = Mutex()
    @Volatile private var lastValidatedToken: String? = null
    @Volatile private var lastValidationAtMs: Long = 0L
    @Volatile private var lastValidationResult: Boolean? = null

    val isAuthenticated: Boolean
        get() = authInterceptor.hasToken() && settingsManager.currentSettings.serverUrl.isNotEmpty()

    // ─── Auth ────────────────────────────────────────────────────────────

    suspend fun login(serverUrl: String, username: String, password: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val normalizedUrl = normalizeServerUrl(serverUrl)
                val normalizedUsername = username.trim()

                // Update settings with server URL first (so Retrofit uses it)
                settingsManager.updateSettings { it.copy(serverUrl = normalizedUrl, username = normalizedUsername) }

                val response = api.login(LoginRequest(normalizedUsername, password))

                if (!response.isSuccessful) {
                    lastError = "Login failed: ${response.code()} - ${response.errorBody()?.string()}"
                    return@withContext false
                }

                val loginResponse = response.body()
                val token = loginResponse?.user?.token

                if (token.isNullOrEmpty()) {
                    lastError = "Server response did not contain authentication token"
                    return@withContext false
                }

                // Save token and update interceptor
                authInterceptor.setToken(token)
                settingsManager.saveAuthToken(token)

                lastError = null
                true
            } catch (e: Exception) {
                lastError = formatConnectionError(e)
                false
            }
        }
    }

    suspend fun loginWithToken(serverUrl: String, token: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val normalizedUrl = normalizeServerUrl(serverUrl)

                // Set server URL so Retrofit uses it
                settingsManager.updateSettings { it.copy(serverUrl = normalizedUrl, useApiToken = true) }

                // Set token and validate it
                authInterceptor.setToken(token)
                settingsManager.saveAuthToken(token)

                val isValid = validateToken(forceRefresh = true, tokenOverride = token)
                if (!isValid) {
                    authInterceptor.setToken(null)
                    settingsManager.clearAuthToken()
                    lastError = "Invalid API token"
                    return@withContext false
                }

                lastError = null
                true
            } catch (e: Exception) {
                authInterceptor.setToken(null)
                settingsManager.clearAuthToken()
                lastError = formatConnectionError(e)
                false
            }
        }
    }

    private fun formatConnectionError(error: Exception): String {
        val mismatch = error.findFingerprintMismatch()
        return if (mismatch != null) {
            "Certificate fingerprint mismatch for ${mismatch.host}. " +
                "Possible MITM attack or server certificate rotation. " +
                "Review server certificate and reset trusted fingerprint if intentional."
        } else {
            "Connection failed: ${error.message}"
        }
    }

    private fun Throwable.findFingerprintMismatch(): SelfSignedCertTrustManager.CertificateFingerprintMismatchException? {
        var current: Throwable? = this
        while (current != null) {
            if (current is SelfSignedCertTrustManager.CertificateFingerprintMismatchException) {
                return current
            }
            current = current.cause
        }
        return null
    }

    suspend fun logout() {
        authInterceptor.setToken(null)
        settingsManager.clearAuthToken()
    }

    suspend fun validateToken(forceRefresh: Boolean = false, tokenOverride: String? = null): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Try to restore token from secure storage
                val token = tokenOverride ?: settingsManager.getAuthToken()
                if (token.isNullOrEmpty()) return@withContext false

                authInterceptor.setToken(token)

                tokenValidationMutex.withLock {
                    getCachedValidation(token, forceRefresh)?.let { return@withContext it }

                    val result = validateTokenWithLightweightEndpoint()
                    cacheValidation(token, result)
                    result
                }
            } catch (e: Exception) {
                false
            }
        }
    }

    private suspend fun validateTokenWithLightweightEndpoint(): Boolean {
        val response = try {
            api.authorize()
        } catch (e: Exception) {
            return false
        }

        if (response.isSuccessful) return true

        // Some Audiobookshelf servers may not expose /api/authorize.
        // Fall back to /api/me (heavier payload), but cached/debounced above.
        if (response.code() == 404 || response.code() == 405) {
            return fallbackValidateTokenViaProfileSync()
        }

        return false
    }

    private suspend fun fallbackValidateTokenViaProfileSync(): Boolean {
        val response = try {
            api.getMe()
        } catch (e: Exception) {
            return false
        }
        return response.isSuccessful
    }

    private fun getCachedValidation(token: String, forceRefresh: Boolean): Boolean? {
        if (forceRefresh) return null
        val cachedResult = lastValidationResult ?: return null
        val isSameToken = token == lastValidatedToken
        val isFresh = (System.currentTimeMillis() - lastValidationAtMs) < TOKEN_VALIDATION_DEBOUNCE_MS
        return if (isSameToken && isFresh) cachedResult else null
    }

    private fun cacheValidation(token: String, result: Boolean) {
        lastValidatedToken = token
        lastValidationResult = result
        lastValidationAtMs = System.currentTimeMillis()
    }

    /** Restore token from secure storage on app startup. */
    suspend fun initializeFromSettings() {
        val token = settingsManager.getAuthToken()
        if (!token.isNullOrEmpty()) {
            authInterceptor.setToken(token)
        }
    }

    // ─── Libraries ───────────────────────────────────────────────────────

    suspend fun getLibraries(): List<Library> = withContext(Dispatchers.IO) {
        try {
            val response = api.getLibraries()
            if (!response.isSuccessful) return@withContext emptyList()

            response.body()?.libraries?.map { apiLib ->
                Library(
                    id = apiLib.id,
                    name = apiLib.name,
                    displayOrder = apiLib.displayOrder,
                    icon = apiLib.icon ?: "audiobook",
                    mediaType = apiLib.mediaType ?: "book",
                    folders = apiLib.folders?.map { f ->
                        Folder(id = f.id, fullPath = f.fullPath, libraryId = apiLib.id)
                    } ?: emptyList()
                )
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ─── Library Items (Paginated batch load) ────────────────────────────

    suspend fun getLibraryItems(libraryId: String, limit: Int = 100): List<AudioBook> =
        withContext(Dispatchers.IO) {
            try {
                val allItems = mutableListOf<AudioBook>()
                var currentPage = 0

                while (true) {
                    val response = api.getLibraryItems(libraryId, limit, currentPage)
                    if (!response.isSuccessful) break

                    val body = response.body() ?: break
                    if (body.results.isEmpty()) break

                    allItems.addAll(body.results.map { mapToAudioBook(it, libraryId) })

                    if (allItems.size >= body.total) break
                    currentPage++
                }

                allItems
            } catch (e: Exception) {
                emptyList()
            }
        }

    // ─── Single Item ─────────────────────────────────────────────────────

    suspend fun getAudioBook(itemId: String): AudioBook? = withContext(Dispatchers.IO) {
        try {
            val response = api.getItem(itemId)
            if (!response.isSuccessful) return@withContext null
            response.body()?.let { mapToAudioBook(it) }
        } catch (e: Exception) {
            null
        }
    }

    // ─── Playback Session ────────────────────────────────────────────────

    suspend fun startPlaybackSession(itemId: String): PlaybackSessionInfo? =
        withContext(Dispatchers.IO) {
            try {
                val request = StartPlaybackRequest(
                    deviceInfo = DeviceInfo(
                        clientName = "NineLivesAudio",
                        deviceId = settingsManager.getDeviceId(),
                    )
                )
                val response = api.startPlaybackSession(itemId, request)
                if (!response.isSuccessful) return@withContext null

                val session = response.body() ?: return@withContext null
                val serverUrl = settingsManager.currentSettings.serverUrl

                PlaybackSessionInfo(
                    id = session.id,
                    itemId = session.libraryItemId,
                    episodeId = session.episodeId,
                    currentTime = session.currentTime,
                    duration = session.duration,
                    mediaType = session.mediaType ?: "book",
                    audioTracks = session.audioTracks?.map { t ->
                        // Build the content URL without embedding the auth token.
                        // Auth is handled via Authorization header in PlaybackManager's
                        // DefaultHttpDataSource.Factory — tokens in URLs leak into
                        // server logs, proxy logs, and Referer headers.
                        val contentUrl = if (t.contentUrl.startsWith("http", ignoreCase = true)) {
                            t.contentUrl
                        } else {
                            val normalizedPath = if (t.contentUrl.startsWith("/")) t.contentUrl else "/${t.contentUrl}"
                            "$serverUrl$normalizedPath"
                        }
                        AudioStreamInfo(
                            index = t.index,
                            codec = t.codec ?: "mp3",
                            title = t.title,
                            duration = t.duration,
                            contentUrl = contentUrl,
                        )
                    } ?: emptyList(),
                    chapters = session.chapters?.map { c ->
                        Chapter(id = c.id, start = c.start, end = c.end, title = c.title)
                    } ?: emptyList(),
                )
            } catch (e: Exception) {
                null
            }
        }

    suspend fun syncSessionProgress(
        sessionId: String,
        currentTime: Double,
        duration: Double,
        timeListened: Double = 0.0,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val response = api.syncSessionProgress(
                sessionId,
                SyncSessionRequest(currentTime, duration, timeListened)
            )
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    suspend fun closeSession(sessionId: String) {
        withContext(Dispatchers.IO) {
            try {
                api.closeSession(sessionId)
            } catch (_: Exception) {}
        }
    }

    // ─── Progress ────────────────────────────────────────────────────────

    suspend fun updateProgress(
        itemId: String,
        currentTime: Double,
        isFinished: Boolean = false,
        duration: Double = 0.0,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val safeTime = currentTime.coerceAtLeast(0.0)
            val progress = when {
                isFinished -> 1.0
                duration > 0.0 -> (safeTime / duration).coerceIn(0.0, 1.0)
                else -> 0.0
            }
            val response = api.updateProgress(
                itemId,
                UpdateProgressRequest(
                    currentTime = safeTime,
                    isFinished = isFinished,
                    progress = progress,
                )
            )
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getUserProgress(itemId: String): UserProgress? = withContext(Dispatchers.IO) {
        try {
            val response = api.getUserProgress(itemId)
            if (!response.isSuccessful) return@withContext null
            response.body()?.let { p ->
                    UserProgress(
                        libraryItemId = p.libraryItemId,
                        currentTime = p.currentTime.seconds,
                        progress = normalizeProgress(p.progress),
                        isFinished = p.isFinished,
                        lastUpdate = if (p.lastUpdate > 0) p.lastUpdate else null,
                    )
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getAllUserProgress(): List<UserProgress> = withContext(Dispatchers.IO) {
        try {
            val response = api.getMe()
            if (!response.isSuccessful) return@withContext emptyList()

            response.body()?.mediaProgress
                ?.filter { it.libraryItemId.isNotEmpty() }
                ?.map { p ->
                    UserProgress(
                        libraryItemId = p.libraryItemId,
                        currentTime = p.currentTime.seconds,
                        progress = normalizeProgress(p.progress),
                        isFinished = p.isFinished,
                        lastUpdate = if (p.lastUpdate > 0) p.lastUpdate else null,
                    )
                } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ─── Listening Sessions ──────────────────────────────────────────────

    suspend fun getListeningSessions(
        libraryItemId: String,
        itemsPerPage: Int = 50,
    ): List<ListeningSession> = withContext(Dispatchers.IO) {
        try {
            val allSessions = mutableListOf<ListeningSession>()
            var currentPage = 0
            val maxPages = 3

            while (currentPage < maxPages) {
                val response = api.getListeningSessions(
                    itemsPerPage = itemsPerPage,
                    page = currentPage,
                )
                if (!response.isSuccessful) break

                val body = response.body() ?: break
                if (body.sessions.isEmpty()) break

                val filtered = body.sessions
                    .filter { it.libraryItemId == libraryItemId }
                    .map { session ->
                        val startedAtMillis = normalizeEpoch(session.startedAt)
                        val updatedAtMillis = normalizeEpoch(session.updatedAt)

                        ListeningSession(
                            id = session.id,
                            libraryItemId = session.libraryItemId,
                            currentTime = session.currentTime.seconds,
                            timeListening = session.timeListening.seconds,
                            startedAt = startedAtMillis,
                            updatedAt = updatedAtMillis,
                            displayTitle = session.displayTitle,
                        )
                    }
                allSessions.addAll(filtered)

                if (currentPage >= body.numPages - 1) break
                currentPage++
            }

            allSessions.sortedByDescending { it.startedAt }
        } catch (e: Exception) {
            lastError = "Failed to load listening sessions: ${e.message}"
            emptyList()
        }
    }

    /** Fetch ALL listening sessions across all books (for stats/dossier). */
    suspend fun getAllListeningSessions(
        itemsPerPage: Int = 50,
    ): List<ListeningSession> = withContext(Dispatchers.IO) {
        try {
            val allSessions = mutableListOf<ListeningSession>()
            var currentPage = 0
            val maxPages = 20

            while (currentPage < maxPages) {
                val response = api.getListeningSessions(
                    itemsPerPage = itemsPerPage,
                    page = currentPage,
                )
                if (!response.isSuccessful) break

                val body = response.body() ?: break
                if (body.sessions.isEmpty()) break

                allSessions.addAll(body.sessions.map { session ->
                    val startedAtMillis = normalizeEpoch(session.startedAt)
                    val updatedAtMillis = normalizeEpoch(session.updatedAt)

                    ListeningSession(
                        id = session.id,
                        libraryItemId = session.libraryItemId,
                        currentTime = session.currentTime.seconds,
                        timeListening = session.timeListening.seconds,
                        startedAt = startedAtMillis,
                        updatedAt = updatedAtMillis,
                        displayTitle = session.displayTitle,
                    )
                })

                if (currentPage >= body.numPages - 1) break
                currentPage++
            }

            allSessions.sortedByDescending { it.startedAt }
        } catch (e: Exception) {
            lastError = "Failed to load listening sessions: ${e.message}"
            emptyList()
        }
    }

    /** Normalize an epoch value that might be seconds or milliseconds to milliseconds. */
    private fun normalizeEpoch(value: Long): Long {
        return if (value in 1..999_999_999_999L) value * 1000 else value
    }

    // ─── Bookmarks ───────────────────────────────────────────────────────

    suspend fun getBookmarks(itemId: String): List<Bookmark> = withContext(Dispatchers.IO) {
        try {
            val response = api.getMe()
            if (!response.isSuccessful) return@withContext emptyList()

            response.body()?.bookmarks
                ?.filter { it.libraryItemId == itemId }
                ?.sortedBy { it.time }
                ?.map { b ->
                    Bookmark(
                        id = b.id,
                        libraryItemId = b.libraryItemId,
                        title = b.title,
                        time = b.time,
                        createdAt = b.createdAt,
                    )
                } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun createBookmark(itemId: String, title: String, time: Double): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val response = api.createBookmark(itemId, CreateBookmarkRequest(title, time))
                response.isSuccessful
            } catch (e: Exception) {
                false
            }
        }

    suspend fun deleteBookmark(itemId: String, time: Double): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val response = api.deleteBookmark(itemId, time)
                response.isSuccessful
            } catch (e: Exception) {
                false
            }
        }

    // ─── Cover Image URL ─────────────────────────────────────────────────

    fun getCoverUrl(itemId: String): String {
        val serverUrl = settingsManager.currentSettings.serverUrl
        if (serverUrl.isBlank() || itemId.isBlank()) return ""
        return "$serverUrl/api/items/${Uri.encode(itemId)}/cover"
    }

    // ─── Mapping Helpers ─────────────────────────────────────────────────

    private fun mapToAudioBook(item: ApiLibraryItem, libraryId: String? = null): AudioBook {
        val metadata = item.media?.metadata
        val audioFiles = item.media?.audioFiles ?: emptyList()
        val firstSeries = metadata?.series?.firstOrNull()
        val serverUrl = settingsManager.currentSettings.serverUrl

        // Resolve series name and sequence. The non-expanded library items endpoint does not
        // populate the series array — it only returns metadata.seriesName as a combined string
        // like "Dungeon Crawler Carl #7". If the array is present, use it directly. Otherwise
        // parse the combined field to extract the name and sequence separately so that all books
        // in the same series share a common seriesName key for grouping.
        val (resolvedSeriesName, resolvedSeriesSequence) = when {
            firstSeries?.name?.isNotBlank() == true -> {
                firstSeries.name to firstSeries.sequence?.takeIf { it.isNotBlank() }
            }
            metadata?.seriesName?.isNotBlank() == true -> {
                parseSeriesNameField(metadata.seriesName)
            }
            else -> null to null
        }

        return AudioBook(
            id = item.id,
            libraryId = libraryId ?: item.libraryId,
            title = metadata?.title?.takeIf { it.isNotBlank() } ?: "Unknown Title",
            author = metadata?.authorName?.takeIf { it.isNotBlank() }
                ?: metadata?.authors?.firstOrNull()?.name?.takeIf { it.isNotBlank() }
                ?: "Unknown Author",
            narrator = metadata?.narratorName?.takeIf { it.isNotBlank() }
                ?: metadata?.narrators?.firstOrNull()?.takeIf { it.isNotBlank() },
            description = metadata?.description,
            coverPath = if (!item.media?.coverPath.isNullOrEmpty()) {
                "$serverUrl/api/items/${Uri.encode(item.id)}/cover"
            } else null,
            duration = (item.media?.duration ?: 0.0).seconds,
            addedAt = item.addedAt,
            seriesName = resolvedSeriesName,
            seriesSequence = resolvedSeriesSequence,
            genres = metadata?.genres ?: emptyList(),
            tags = metadata?.tags ?: emptyList(),
            audioFiles = audioFiles.mapIndexed { idx, af ->
                AudioFile(
                    id = af.ino ?: idx.toString(),
                    ino = af.ino ?: "",
                    index = af.index ?: idx,
                    duration = (af.duration ?: 0.0).seconds,
                    filename = af.metadata?.filename?.takeIf { it.isNotBlank() } ?: "track_${idx + 1}",
                    mimeType = af.mimeType,
                    size = af.metadata?.size ?: 0,
                )
            },
            chapters = item.media?.chapters
                ?.filter { c -> c.start >= 0.0 && c.end > c.start }
                ?.map { c -> Chapter(id = c.id, start = c.start, end = c.end, title = c.title.ifBlank { "Chapter ${c.id}" }) }
                ?: emptyList(),
            currentTime = (item.userMediaProgress?.currentTime ?: 0.0).seconds,
            progress = normalizeProgress(item.userMediaProgress?.progress ?: 0.0),
            isFinished = item.userMediaProgress?.isFinished ?: false,
        )
    }

    /**
     * Parses the ABS combined seriesName field (e.g. "Dungeon Crawler Carl #7") into a
     * (name, sequence) pair. The non-expanded library items endpoint returns this single
     * concatenated string instead of the structured series array that the expanded endpoint
     * provides. Supported formats:
     *   "Series Name #7"    → ("Series Name", "7")
     *   "Series Name #1.5"  → ("Series Name", "1.5")
     *   "Series Name"       → ("Series Name", null)
     */
    private fun parseSeriesNameField(seriesName: String): Pair<String?, String?> {
        val trimmed = seriesName.trim()
        val hashMatch = Regex("""^(.+?)\s*#([\d.]+)\s*$""").find(trimmed)
        return if (hashMatch != null) {
            hashMatch.groupValues[1].trim() to hashMatch.groupValues[2]
        } else {
            trimmed to null
        }
    }

    private fun normalizeProgress(value: Double): Double {
        val nonNegative = value.coerceAtLeast(0.0)
        return if (nonNegative > 1.0) {
            (nonNegative / 100.0).coerceIn(0.0, 1.0)
        } else {
            nonNegative.coerceIn(0.0, 1.0)
        }
    }

    private fun normalizeServerUrl(url: String): String {
        var normalized = url.trim().replace("\\", "/")
        if (normalized.isEmpty()) return ""

        if ("://" !in normalized) {
            normalized = when {
                normalized.startsWith("https:", ignoreCase = true) ->
                    "https://${normalized.substringAfter(':').trimStart('/')}"
                normalized.startsWith("http:", ignoreCase = true) ->
                    "http://${normalized.substringAfter(':').trimStart('/')}"
                else -> "https://$normalized"
            }
        }

        return normalized.trimEnd('/').removeSuffix("/api")
    }
}
