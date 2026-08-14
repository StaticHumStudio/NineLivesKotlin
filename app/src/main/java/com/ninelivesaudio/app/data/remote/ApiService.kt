package com.ninelivesaudio.app.data.remote

import android.net.Uri
import android.util.Log
import com.ninelivesaudio.app.data.remote.dto.*
import com.ninelivesaudio.app.domain.model.*
import com.ninelivesaudio.app.service.SettingsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Outcome of validating an auth token against the server.
 *
 * The distinction between [INVALID] and [UNREACHABLE] is load-bearing: only
 * [INVALID] (the server actively rejected the credentials) is a reason to log
 * the user out. [UNREACHABLE] means we could not get a verdict (offline, server
 * down, no server URL yet) and the stored token must be preserved.
 */
enum class TokenValidationResult { VALID, INVALID, UNREACHABLE }

/**
 * Outcome of an explicit username/password login attempt.
 *
 * The distinction between [REJECTED] and [UNREACHABLE] is load-bearing the
 * same way it is for [TokenValidationResult]: only [REJECTED] (the server
 * actively rejected the credentials) is a real login failure. [UNREACHABLE]
 * means no verdict was reached, and retained-session repair may still apply.
 */
enum class CredentialLoginResult { SUCCESS, REJECTED, UNREACHABLE }

/**
 * Classifies a non-2xx login HTTP status. 4xx is the server rejecting the
 * credentials (auth-shaped codes like 401/403 included); anything else (5xx,
 * unexpected codes) is a transient inability to reach a verdict.
 */
internal fun classifyLoginHttpFailure(code: Int): CredentialLoginResult = when (code) {
    in 400..499 -> CredentialLoginResult.REJECTED
    else -> CredentialLoginResult.UNREACHABLE
}

internal data class AuthSessionIdentity(
    val generation: Long,
    val token: String,
    val serverUrl: String,
)

internal data class StoredTokenValidation(
    val session: AuthSessionIdentity,
    val result: TokenValidationResult,
)

/** Barrier for components, such as Android Auto, that can start before app initialization. */
internal class AuthReadiness {
    private val initializationMutex = Mutex()
    @Volatile private var initialized = false

    suspend fun awaitOrInitialize(initializer: suspend () -> Boolean) {
        if (initialized) return
        initializationMutex.withLock {
            if (!initialized) {
                // Latch only on a fully successful restore. A false return
                // (degraded startup: storage unavailable or token unreadable)
                // leaves the gate retryable, exactly like failure or
                // cancellation, so the next service or app request reruns the
                // whole restore once storage recovers. Latching a degraded
                // init would strand the interceptor tokenless while a valid
                // token sits in recovered storage, and the next validation
                // would 401 unauthenticated and clear that valid credential.
                initialized = initializer()
            }
        }
    }
}

/**
 * Classifies an HTTP status code from a token-validation endpoint.
 * 2xx means the server accepted the token, 401/403 means it rejected it, and
 * anything else (5xx, unexpected codes) is treated as a transient inability to
 * reach a verdict rather than an auth failure.
 */
internal fun classifyValidationStatus(code: Int): TokenValidationResult = when {
    code in 200..299 -> TokenValidationResult.VALID
    code == 401 || code == 403 -> TokenValidationResult.INVALID
    else -> TokenValidationResult.UNREACHABLE
}

/** Stored-token validation must wait for secure storage restoration. */
internal fun validationNeedsStoredAuth(tokenOverride: String?): Boolean = tokenOverride == null

internal fun authSessionMatches(
    expected: AuthSessionIdentity,
    current: AuthSessionIdentity?,
): Boolean = expected == current

/** Runtime auth and cache identity must change before fallible secure storage. */
internal suspend fun applyAuthTokenMutation(
    updateRuntimeAuth: () -> Unit,
    recordMutation: () -> Unit,
    persistSecureStorage: suspend () -> Unit,
) {
    updateRuntimeAuth()
    recordMutation()
    persistSecureStorage()
}

/**
 * Restores settings (and the auth generation) after a password login attempt
 * that did not succeed, mirroring [rollbackFailedTokenLogin]'s intent for the
 * password flow: a failed attempt against a new URL must not leave settings
 * pointing at the new, never-authenticated server — that would later validate
 * the OLD token against the NEW server, and a 401 there would wipe a token
 * still valid for the original server.
 *
 * Wrapped so a rollback failure (e.g. the encrypted store itself is
 * unavailable) never clobbers the real error the caller is about to surface.
 */
internal suspend fun rollbackFailedPasswordLogin(
    restoreSettings: suspend () -> Unit,
    recordMutation: () -> Unit,
    onRollbackFailure: (Throwable) -> Unit = {},
) {
    runCatching {
        restoreSettings()
        recordMutation()
    }.onFailure(onRollbackFailure)
}

internal suspend fun rollbackFailedTokenLogin(
    previousToken: String?,
    attemptedToken: String,
    readStoredToken: suspend () -> String?,
    replaceStoredToken: suspend (expected: String, replacement: String?) -> Unit,
    restorePreviousSettings: suspend () -> Unit = {},
    restoreRuntimeAuth: (String?) -> Unit,
    recordMutation: () -> Unit,
): Boolean {
    val storedToken = readStoredToken()
    if (storedToken != attemptedToken && storedToken != previousToken) return false
    restorePreviousSettings()
    if (storedToken == attemptedToken) replaceStoredToken(attemptedToken, previousToken)
    restoreRuntimeAuth(previousToken)
    recordMutation()
    return true
}

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
        private const val TAG = "ApiService"
        private const val TOKEN_VALIDATION_DEBOUNCE_MS = 15_000L
    }

    var lastError: String? = null
        private set

    private val tokenValidationMutex = Mutex()
    private val authMutationMutex = Mutex()
    private val authReadiness = AuthReadiness()
    private var authGeneration: Long = 0L
    @Volatile private var lastValidatedToken: String? = null
    @Volatile private var lastValidationAtMs: Long = 0L
    @Volatile private var lastValidationResult: TokenValidationResult? = null

    val isAuthenticated: Boolean
        get() = authInterceptor.hasToken() &&
            validatedServerBaseUrl(settingsManager.currentSettings.serverUrl) != null

    // ─── Auth ────────────────────────────────────────────────────────────

    suspend fun login(serverUrl: String, username: String, password: String): CredentialLoginResult {
        return withContext(Dispatchers.IO) {
            authMutationMutex.withLock {
                // Snapshot so a failed attempt can restore the PREVIOUS server/
                // username instead of leaving settings pointing at the new,
                // never-authenticated server. Without this, validateRetainedSession
                // would validate the old token against the new server and a 401
                // there would wipe a token still valid for the original server.
                val previousSettings = settingsManager.currentSettings
                try {
                    val normalizedUrl = normalizeServerUrl(serverUrl)
                    val normalizedUsername = username.trim()

                    // Update settings with server URL first (so Retrofit uses it)
                    settingsManager.updateSettings {
                        it.copy(serverUrl = normalizedUrl, username = normalizedUsername)
                    }
                    recordAuthMutation()

                    val response = api.login(LoginRequest(normalizedUsername, password))

                    if (!response.isSuccessful) {
                        lastError = "Login failed: ${response.code()} - ${response.errorBody()?.string()}"
                        rollbackFailedPasswordLogin(
                            restoreSettings = { settingsManager.saveSettings(previousSettings) },
                            recordMutation = { recordAuthMutation() },
                            onRollbackFailure = { e ->
                                Log.e(TAG, "login: Failed to roll back settings after a rejected login", e)
                            },
                        )
                        return@withLock classifyLoginHttpFailure(response.code())
                    }

                    val loginResponse = response.body()
                    val token = loginResponse?.user?.token

                    if (token.isNullOrEmpty()) {
                        lastError = "Server response did not contain authentication token"
                        rollbackFailedPasswordLogin(
                            restoreSettings = { settingsManager.saveSettings(previousSettings) },
                            recordMutation = { recordAuthMutation() },
                            onRollbackFailure = { e ->
                                Log.e(TAG, "login: Failed to roll back settings after a rejected login", e)
                            },
                        )
                        return@withLock CredentialLoginResult.REJECTED
                    }

                    // Save token and update interceptor
                    applyAuthTokenMutation(
                        updateRuntimeAuth = { authInterceptor.setToken(token) },
                        recordMutation = { recordAuthMutation() },
                        persistSecureStorage = { settingsManager.saveAuthToken(token) },
                    )

                    lastError = null
                    CredentialLoginResult.SUCCESS
                } catch (e: Exception) {
                    lastError = formatConnectionError(e)
                    rollbackFailedPasswordLogin(
                        restoreSettings = { settingsManager.saveSettings(previousSettings) },
                        recordMutation = { recordAuthMutation() },
                        onRollbackFailure = { rollbackError ->
                            Log.e(TAG, "login: Failed to roll back settings after an unreachable login", rollbackError)
                        },
                    )
                    CredentialLoginResult.UNREACHABLE
                }
            }
        }
    }

    suspend fun loginWithToken(serverUrl: String, token: String): Boolean {
        return withContext(Dispatchers.IO) {
            authMutationMutex.withLock {
                val previousSettings = settingsManager.currentSettings
                val previousToken = settingsManager.getAuthToken()
                val normalizedToken = token.trim()
                try {
                    val normalizedUrl = normalizeServerUrl(serverUrl)

                    // Set server URL so Retrofit uses it
                    settingsManager.updateSettings {
                        it.copy(serverUrl = normalizedUrl, useApiToken = true)
                    }

                    // Set token and validate it
                    applyAuthTokenMutation(
                        updateRuntimeAuth = { authInterceptor.setToken(normalizedToken) },
                        recordMutation = { recordAuthMutation() },
                        persistSecureStorage = { settingsManager.saveAuthToken(normalizedToken) },
                    )

                    when (validateTokenDetailed(forceRefresh = true, tokenOverride = normalizedToken)) {
                        TokenValidationResult.VALID -> {
                            lastError = null
                            true
                        }
                        TokenValidationResult.INVALID -> {
                            // Wrapped in runCatching: an uncaught rollback failure
                            // here (e.g. the check() below) falls through to the
                            // outer catch, which re-runs rollback and replaces the
                            // real "Invalid API token" verdict with a confusing
                            // "Auth session changed during token rollback" message.
                            runCatching {
                                rollbackFailedTokenLogin(
                                    previousToken = previousToken,
                                    attemptedToken = normalizedToken,
                                    readStoredToken = { settingsManager.getAuthToken() },
                                    replaceStoredToken = { expected, replacement ->
                                        check(settingsManager.replaceAuthTokenIfCurrent(expected, replacement)) {
                                            "Auth session changed during token rollback"
                                        }
                                    },
                                    restorePreviousSettings = { settingsManager.saveSettings(previousSettings) },
                                    restoreRuntimeAuth = { authInterceptor.setToken(it) },
                                    recordMutation = { recordAuthMutation() },
                                )
                            }.onFailure { e ->
                                Log.e(TAG, "loginWithToken: Rollback failed after an invalid token", e)
                            }
                            lastError = "Invalid API token"
                            false
                        }
                        TokenValidationResult.UNREACHABLE -> {
                            // Could not reach the server to verify. Keep the token
                            // so the session works once the server is reachable.
                            lastError = "Could not reach server to verify the token. Check the URL and your connection, then try again."
                            false
                        }
                    }
                } catch (e: Exception) {
                    runCatching {
                        rollbackFailedTokenLogin(
                            previousToken = previousToken,
                            attemptedToken = normalizedToken,
                            readStoredToken = { settingsManager.getAuthToken() },
                            replaceStoredToken = { expected, replacement ->
                                check(settingsManager.replaceAuthTokenIfCurrent(expected, replacement)) {
                                    "Auth session changed during token rollback"
                                }
                            },
                            restorePreviousSettings = { settingsManager.saveSettings(previousSettings) },
                            restoreRuntimeAuth = { authInterceptor.setToken(it) },
                            recordMutation = { recordAuthMutation() },
                        )
                    }.exceptionOrNull()?.let(e::addSuppressed)
                    lastError = formatConnectionError(e)
                    false
                }
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
        authMutationMutex.withLock {
            applyAuthTokenMutation(
                updateRuntimeAuth = { authInterceptor.setToken(null) },
                recordMutation = { recordAuthMutation() },
                persistSecureStorage = { settingsManager.clearAuthToken() },
            )
        }
    }

    /**
     * Validates one stable stored session. The HTTP call itself runs OUTSIDE
     * authMutationMutex — it can take 30s+ to time out, and holding the lock
     * across it blocked login/logout for that whole window. Session identity
     * is captured under the lock before the call and re-compared under the
     * lock after, so a verdict computed against a session that a concurrent
     * login/logout has since replaced is discarded rather than applied.
     */
    internal suspend fun validateStoredTokenSession(
        forceRefresh: Boolean = false,
    ): StoredTokenValidation? {
        awaitAuthReady()
        val session = authMutationMutex.withLock {
            val token = settingsManager.getAuthToken()?.takeIf { it.isNotEmpty() }
                ?: return@withLock null
            currentAuthSessionLocked(token)
        } ?: return null

        val result = validateTokenDetailed(forceRefresh, tokenOverride = session.token)

        return authMutationMutex.withLock {
            if (!authSessionMatches(session, currentAuthSessionLocked(settingsManager.getAuthToken()))) {
                return@withLock null
            }
            StoredTokenValidation(session = session, result = result)
        }
    }

    internal suspend fun isCurrentAuthSession(expected: AuthSessionIdentity): Boolean =
        authMutationMutex.withLock {
            authSessionMatches(expected, currentAuthSessionLocked(settingsManager.getAuthToken()))
        }

    /** Clears an invalid session only if no newer auth mutation replaced it. */
    internal suspend fun logoutIfCurrentSession(expected: AuthSessionIdentity): Boolean =
        authMutationMutex.withLock {
            if (!authSessionMatches(expected, currentAuthSessionLocked(settingsManager.getAuthToken()))) {
                return@withLock false
            }
            applyAuthTokenMutation(
                updateRuntimeAuth = { authInterceptor.setToken(null) },
                recordMutation = { recordAuthMutation() },
                persistSecureStorage = { settingsManager.clearAuthToken() },
            )
            true
        }

    private fun currentAuthSessionLocked(token: String?): AuthSessionIdentity? =
        token?.takeIf { it.isNotEmpty() }?.let {
            AuthSessionIdentity(
                generation = authGeneration,
                token = it,
                serverUrl = settingsManager.currentSettings.serverUrl,
            )
        }

    suspend fun awaitAuthReady() {
        initializeFromSettings()
    }

    private fun clearValidationCache() {
        lastValidatedToken = null
        lastValidationAtMs = 0L
        lastValidationResult = null
    }

    /** Validation verdicts belong to one exact auth generation and server. */
    private fun recordAuthMutation() {
        authGeneration++
        clearValidationCache()
    }

    /**
     * Backwards-compatible boolean check: true only when the session is known
     * to be valid right now. A transient network failure returns false here
     * but is NOT a signal to log out — callers that clear the token must use
     * [validateTokenDetailed] and only act on [TokenValidationResult.INVALID].
     */
    suspend fun validateToken(forceRefresh: Boolean = false, tokenOverride: String? = null): Boolean =
        validateTokenDetailed(forceRefresh, tokenOverride) == TokenValidationResult.VALID

    /**
     * Validates the stored (or supplied) token and reports a three-state result:
     * VALID (server accepted it), INVALID (server rejected it — 401/403, safe to
     * log out), or UNREACHABLE (no token, no server URL, or a network/server
     * error — must NOT trigger a logout, the token may still be good).
     */
    suspend fun validateTokenDetailed(
        forceRefresh: Boolean = false,
        tokenOverride: String? = null,
    ): TokenValidationResult {
        // Explicit-token login already owns authMutationMutex and has installed
        // its token. Stored-token validation must first let serialized startup
        // restoration install the current token into the interceptor.
        if (validationNeedsStoredAuth(tokenOverride)) {
            awaitAuthReady()
        }

        return withContext(Dispatchers.IO) {
            try {
                val token = tokenOverride ?: settingsManager.getAuthToken()
                // No token at all is "nothing to validate", not a server
                // rejection — report UNREACHABLE so it never drives a logout.
                if (token.isNullOrEmpty()) return@withContext TokenValidationResult.UNREACHABLE

                // Without a server URL the only request we could make would hit
                // the placeholder base URL (http://localhost) and fail. That is
                // not an auth failure, so report UNREACHABLE and never log out.
                if (settingsManager.currentSettings.serverUrl.isBlank()) {
                    return@withContext TokenValidationResult.UNREACHABLE
                }

                tokenValidationMutex.withLock {
                    getCachedValidation(token, forceRefresh)?.let { return@withContext it }

                    val result = validateTokenWithLightweightEndpoint()
                    cacheValidation(token, result)
                    result
                }
            } catch (e: Exception) {
                // Network/transport error — unreachable, not unauthorized.
                TokenValidationResult.UNREACHABLE
            }
        }
    }

    private suspend fun validateTokenWithLightweightEndpoint(): TokenValidationResult {
        val response = try {
            api.authorize()
        } catch (e: Exception) {
            return TokenValidationResult.UNREACHABLE
        }

        // Some Audiobookshelf servers may not expose /api/authorize.
        // Fall back to /api/me (heavier payload), but cached/debounced above.
        if (response.code() == 404 || response.code() == 405) {
            return fallbackValidateTokenViaProfileSync()
        }

        return classifyValidationStatus(response.code())
    }

    private suspend fun fallbackValidateTokenViaProfileSync(): TokenValidationResult {
        val response = try {
            api.getMe()
        } catch (e: Exception) {
            return TokenValidationResult.UNREACHABLE
        }
        return classifyValidationStatus(response.code())
    }

    private fun getCachedValidation(token: String, forceRefresh: Boolean): TokenValidationResult? {
        if (forceRefresh) return null
        val cachedResult = lastValidationResult ?: return null
        val isSameToken = token == lastValidatedToken
        val isFresh = (System.currentTimeMillis() - lastValidationAtMs) < TOKEN_VALIDATION_DEBOUNCE_MS
        return if (isSameToken && isFresh) cachedResult else null
    }

    private fun cacheValidation(token: String, result: TokenValidationResult) {
        // Only cache definitive verdicts. UNREACHABLE is transient: caching it
        // would make a forced foreground reachability check return the stale
        // "unreachable" answer for up to the debounce window even after the
        // server comes back, delaying recovery.
        if (result == TokenValidationResult.UNREACHABLE) return
        lastValidatedToken = token
        lastValidationResult = result
        lastValidationAtMs = System.currentTimeMillis()
    }

    /** Restore token from secure storage on app startup. */
    suspend fun initializeFromSettings() {
        authReadiness.awaitOrInitialize {
            // A service can start before NineLivesApp's initialization coroutine.
            // Load settings here too so Android Auto can safely trigger the same
            // idempotent startup path instead of waiting on app-owned work.
            authMutationMutex.withLock {
                settingsManager.loadSettings()
                // A corrupt token entry must degrade to logged-out networking,
                // not crash the app scope or the media service that got here
                // first. loadSettings already published hasAuthToken = false
                // for this case.
                // Cancellation must escape so AuthReadiness stays retryable;
                // a swallowed CancellationException here would latch the gate
                // with a null token and poison every later validation.
                var tokenReadable = true
                val token = try {
                    settingsManager.getAuthToken()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (e: Exception) {
                    Log.e(TAG, "initializeFromSettings: Auth token unreadable, starting logged out", e)
                    tokenReadable = false
                    null
                }
                authInterceptor.setToken(token)
                recordAuthMutation()
                // Latch readiness only when the restore ran against healthy
                // storage. A degraded run must stay retryable so the token
                // reaches the interceptor once storage recovers.
                tokenReadable && !settingsManager.storageUnavailable.value
            }
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
                    // A page smaller than the requested limit is the last page.
                    // This also guarantees termination if a misbehaving server
                    // keeps reporting a `total` larger than it ever delivers.
                    if (body.results.size < limit) break
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
