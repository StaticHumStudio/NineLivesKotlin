package com.ninelivesaudio.app.data.remote

import android.net.Uri
import android.util.Log
import com.ninelivesaudio.app.data.remote.dto.*
import com.ninelivesaudio.app.domain.model.*
import com.ninelivesaudio.app.service.SettingsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.Response
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
    restoreRuntimeAuth: () -> Unit = {},
) {
    runCatching {
        restoreRuntimeAuth()
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
    restoreRuntimeAuth(previousToken)
    recordMutation()
    restorePreviousSettings()
    if (storedToken == attemptedToken) replaceStoredToken(attemptedToken, previousToken)
    return true
}

/** Catalog rows receive their durable owner envelope at the remote boundary. */
internal fun namespaceIncomingLibrary(scope: ActiveRemoteScope, library: Library): Library =
    library.copy(
        id = scope.encodeIncoming(library.id),
        folders = library.folders.map { folder ->
            folder.copy(libraryId = scope.encodeIncoming(folder.libraryId))
        },
    )

/** Book and library identities share one owner envelope, never a guessed prefix. */
internal fun namespaceIncomingBook(
    scope: ActiveRemoteScope,
    book: AudioBook,
    rawLibraryId: String?,
): AudioBook = book.copy(
    id = scope.encodeIncoming(book.id),
    libraryId = rawLibraryId?.takeIf { it.isNotBlank() }?.let(scope::encodeIncoming),
)

/** Listening history arrives with server IDs and must match owner-scoped catalog IDs. */
internal fun namespaceIncomingListeningSession(
    ownerKey: String,
    session: ApiListeningSession,
): ListeningSession = ListeningSession(
    id = session.id,
    libraryItemId = RemoteIdCodec.encode(ownerKey, session.libraryItemId),
    currentTime = session.currentTime.seconds,
    timeListening = session.timeListening.seconds,
    startedAt = normalizeEpoch(session.startedAt),
    updatedAt = normalizeEpoch(session.updatedAt),
    displayTitle = session.displayTitle,
)

/** Normalize an epoch value that might be seconds or milliseconds to milliseconds. */
private fun normalizeEpoch(value: Long): Long =
    if (value in 1..999_999_999_999L) value * 1000 else value

/** A raw or foreign durable ID is not an authorization to make a catalog request. */
internal fun catalogEgressId(scope: ActiveRemoteScope, encodedId: String): String? =
    scope.decodeForEgress(encodedId)

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
        private const val LISTENING_SESSIONS_PAGE_SIZE = 50
    }

    var lastError: String? = null
        private set

    private val tokenValidationMutex = Mutex()
    private val authMutationMutex = Mutex()
    private val committedScopeMonitor = Any()
    private var committedAuthFence = 0L
    @Volatile private var committedScopeMonitorObserverForTest: (() -> Unit)? = null
    private val authReadiness = AuthReadiness()
    private val scopeGeneration = MutableStateFlow(0L)
    @Volatile private var authGeneration: Long = 0L
    @Volatile private var lastPersistedAuthRecord: StoredAuthRecord? = null
    @Volatile private var lastValidatedSession: AuthSessionIdentity? = null
    @Volatile private var lastValidationAtMs: Long = 0L
    @Volatile private var lastValidationResult: TokenValidationResult? = null

    val isAuthenticated: Boolean
        get() = authInterceptor.hasTokenFor(settingsManager.currentSettings.serverUrl) &&
            validatedServerBaseUrl(settingsManager.currentSettings.serverUrl) != null

    internal suspend fun captureRemoteTarget(): RemoteTarget? {
        awaitAuthReady()
        return authMutationMutex.withLock { currentRemoteTargetLocked() }
    }

    /**
     * Captures one immutable route, bearer, and generation for an HTTP operation.
     * Callers that already own [authMutationMutex] must use the locked helper.
     */
    internal suspend fun captureFrozenRemoteRequest(): FrozenRemoteRequest? {
        awaitAuthReady()
        return authMutationMutex.withLock { frozenRemoteRequestLocked() }
    }

    internal suspend fun isCurrentFrozenRemoteRequest(expected: FrozenRemoteRequest): Boolean =
        authMutationMutex.withLock { isCurrentFrozenRemoteRequestLocked(expected) }

    /** Captures a confirmed owner plus C1b's exact immutable request identity. */
    internal suspend fun captureActiveRemoteScope(): ActiveRemoteScope? =
        captureFrozenRemoteRequest()?.takeIf { it.owner != null }?.let { frozen ->
            synchronized(committedScopeMonitor) { ActiveRemoteScope(frozen, committedAuthFence) }
        }

    /** Delegates to C1b instead of comparing only owner or target identity. */
    internal suspend fun isCurrentActiveRemoteScope(scope: ActiveRemoteScope): Boolean =
        isCurrentFrozenRemoteRequest(scope.frozenRequest)

    /**
     * Publishes a short in-memory state change only while the exact captured
     * scope is still installed. The callback cannot suspend, which keeps the
     * auth mutation lock free of IO, coroutine locks, and database work.
     */
    internal suspend fun <T> publishIfCurrentActiveRemoteScope(
        scope: ActiveRemoteScope,
        publish: () -> T,
    ): T? {
        awaitAuthReady()
        return authMutationMutex.withLock {
            if (isCurrentCommittedFrozenRequestLocked(scope.frozenRequest)) publish() else null
        }
    }

    /**
     * Linearizes one checked durable publication against auth invalidation.
     * The callback is deliberately non-suspending and may only perform its
     * direct synchronous commit.
     */
    internal fun <T> commitIfCurrentActiveRemoteScope(
        scope: ActiveRemoteScope,
        publish: () -> T,
    ): T? = synchronized(committedScopeMonitor) {
        if (scope.committedAuthFence == committedAuthFence &&
            isCurrentCommittedFrozenRequestLocked(scope.frozenRequest)
        ) publish() else null
    }

    /** Test timing receipt only. It cannot influence authorization or publication. */
    internal fun setCommittedScopeMonitorObserverForTest(observer: (() -> Unit)?) {
        synchronized(committedScopeMonitor) {
            committedScopeMonitorObserverForTest = observer
        }
    }

    /**
     * Every active-owner collector must restart from this full C1b identity,
     * not merely selected settings or an owner prefix. Settings publication
     * covers route revisions while [scopeGeneration] covers bearer, owner, and
     * auth generation mutation.
     */
    internal val activeRemoteScope: Flow<ActiveRemoteScope?> = combine(
        settingsManager.settings,
        scopeGeneration,
    ) { _, _ -> captureActiveRemoteScope() }

    /**
     * Binds a legacy, ownerless session only after its route and bearer are
     * frozen. The network round trip deliberately happens outside the auth
     * mutation mutex, then the same captured record is rechecked before any
     * durable owner is published.
     */
    internal suspend fun resolveRemoteTarget(): RemoteTarget? {
        awaitAuthReady()
        val frozen = authMutationMutex.withLock { frozenRemoteRequestLocked() } ?: return null
        frozen.owner?.let { return it }

        val response = try {
            api.getMe(RemoteDispatchTag.frozen(frozen))
        } catch (stale: StaleRemoteRequestException) {
            return null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        return authMutationMutex.withLock {
            beginCommittedAuthMutation()
            val accepted = acceptDirectResponse(
                response = response,
                dispatch = RemoteDispatchTag.frozen(frozen),
                isCurrent = { isCurrentFrozenRemoteRequestLocked(frozen) },
            ) ?: return@withLock null
            val accountId = accepted.takeIf { it.isSuccessful }
                ?.body()?.id?.takeIf { it.isNotBlank() }
                ?: run {
                    closeUnpublishedResponse(accepted)
                    return@withLock null
                }
            val original = lastPersistedAuthRecord ?: return@withLock null
            lastPersistedAuthRecord = null
            val updated = try {
                settingsManager.persistResolvedAuthOwnerIfCurrent(original, accountId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The settings method restores its captured record and marker
                // after a failed checked commit. Do not publish a target until
                // the complete owner binding has reached durable storage.
                lastPersistedAuthRecord = original.takeIf { settingsManager.getAuthRecord() == it }
                return@withLock null
            } ?: return@withLock null
            lastPersistedAuthRecord = updated
            recordAuthMutation()
            currentRemoteTargetLocked()
        }
    }

    internal suspend fun isCurrentRemoteTarget(expected: RemoteTarget): Boolean =
        authMutationMutex.withLock { expected == currentRemoteTargetLocked() }

    private suspend fun currentRemoteTargetLocked(): RemoteTarget? {
        val record = lastPersistedAuthRecord
        val stored = try {
            settingsManager.getAuthRecord()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        if (record != stored) return null
        return captureRemoteTarget(
            record, settingsManager.currentSettings.serverUrl, authGeneration,
            runtimeAuthMatches = record != null && authInterceptor.matches(record.token, record.serverUrl.orEmpty()),
        )
    }

    private suspend fun frozenRemoteRequestLocked(): FrozenRemoteRequest? {
        val record = lastPersistedAuthRecord ?: return null
        val stored = try {
            settingsManager.getAuthRecord()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        if (record != stored) return null
        val route = ServerRoute.parse(record.serverUrl) ?: return null
        if (route != ServerRoute.parse(settingsManager.currentSettings.serverUrl)) return null
        val bearer = authInterceptor.captureFrozenBearer(record.token, route, authGeneration) ?: return null
        return FrozenRemoteRequest(
            route = route,
            owner = captureRemoteTarget(record, settingsManager.currentSettings.serverUrl, authGeneration, true),
            bearer = bearer,
            routeRevision = settingsManager.currentRouteRevision,
        )
    }

    private suspend fun isCurrentFrozenRemoteRequestLocked(expected: FrozenRemoteRequest): Boolean {
        val current = frozenRemoteRequestLocked() ?: return false
        return current.route == expected.route && current.owner == expected.owner &&
            current.routeRevision == expected.routeRevision &&
            current.bearer == expected.bearer
    }

    /**
     * Publication holds authMutationMutex, so this must stay entirely in
     * memory. lastPersistedAuthRecord is assigned only after its matching
     * secure-storage commit succeeds.
     */
    private fun isCurrentCommittedFrozenRequestLocked(expected: FrozenRemoteRequest): Boolean {
        val record = lastPersistedAuthRecord ?: return false
        val selectedServerUrl = settingsManager.currentSettings.serverUrl
        val route = ServerRoute.parse(record.serverUrl) ?: return false
        if (
            route != expected.route ||
            route != ServerRoute.parse(selectedServerUrl) ||
            settingsManager.currentRouteRevision != expected.routeRevision
        ) return false
        val generation = authGeneration
        val bearer = authInterceptor.captureFrozenBearer(record.token, route, generation) ?: return false
        val target = captureRemoteTarget(
            record = record,
            selectedServerUrl = selectedServerUrl,
            authGeneration = generation,
            runtimeAuthMatches = authInterceptor.matches(record.token, record.serverUrl.orEmpty()),
        )
        return bearer == expected.bearer && target == expected.owner
    }

    /**
     * Lock-free verification for code that already owns tokenValidationMutex.
     * A concurrent auth mutation can only make this return false or be caught
     * again by the tagged interceptors. It never authorizes a changed record.
     */
    private suspend fun isCurrentFrozenRemoteRequestSnapshot(expected: FrozenRemoteRequest): Boolean {
        val record = lastPersistedAuthRecord ?: return false
        val stored = try {
            settingsManager.getAuthRecord()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        }
        if (record != stored) return false
        val route = ServerRoute.parse(record.serverUrl) ?: return false
        if (route != expected.route || route != ServerRoute.parse(settingsManager.currentSettings.serverUrl)) return false
        val bearer = authInterceptor.captureFrozenBearer(record.token, route, authGeneration) ?: return false
        val owner = captureRemoteTarget(record, settingsManager.currentSettings.serverUrl, authGeneration, true)
        return bearer == expected.bearer && owner == expected.owner &&
            settingsManager.currentRouteRevision == expected.routeRevision
    }

    /**
     * Applies a frozen request tag and rejects a response once its original
     * route, bearer, or generation is no longer active. Retrofit has already
     * consumed JSON bodies here, so only still-owned error or stream bodies
     * are closed. Closing raw() would hit Retrofit's NoContent placeholder.
     */
    private suspend fun <T, R> dispatchFrozen(
        frozen: FrozenRemoteRequest,
        request: suspend (RemoteDispatchTag) -> Response<T>,
        publish: (Response<T>) -> R,
    ): R? {
        if (!authMutationMutex.withLock { isCurrentFrozenRemoteRequestLocked(frozen) }) return null
        val response = try {
            request(RemoteDispatchTag.frozen(frozen))
        } catch (stale: StaleRemoteRequestException) {
            return null
        }
        return authMutationMutex.withLock {
            if (!isCurrentFrozenRemoteRequestLocked(frozen) ||
                !frozen.route.contains(response.raw().request.url)) {
                closeUnpublishedResponse(response)
                null
            } else {
                publish(response)
            }
        }
    }

    /**
     * Reusable frozen dispatch for later owner-scoped API methods. The caller
     * supplies a scope, never a bearer or mutable selected-server route.
     */
    internal suspend fun <T, R> dispatchActiveRemoteScope(
        scope: ActiveRemoteScope,
        request: suspend (RemoteDispatchTag) -> Response<T>,
        publish: (Response<T>) -> R,
    ): R? = dispatchFrozen(scope.frozenRequest, request, publish)

    /**
     * Direct auth/profile calls cannot use [dispatchFrozen] because login and
     * owner resolution have distinct publication and rollback work. They still
     * need the exact same final-route and captured-scope fence before status or
     * body data is observed.
     */
    private suspend fun <T> acceptDirectResponse(
        response: Response<T>,
        dispatch: RemoteDispatchTag,
        isCurrent: suspend () -> Boolean,
    ): Response<T>? {
        if (!isCurrent() || !dispatch.route.contains(response.raw().request.url)) {
            closeUnpublishedResponse(response)
            return null
        }
        return response
    }

    private fun isCurrentNoBearerDispatch(dispatch: RemoteDispatchTag): Boolean =
        dispatch.bearer == null && dispatch.routeRevision != null &&
            dispatch.routeRevision == settingsManager.currentRouteRevision &&
            dispatch.route == ServerRoute.parse(settingsManager.currentSettings.serverUrl)

    private fun closeUnpublishedResponse(response: Response<*>) {
        runCatching { response.errorBody()?.close() }
        runCatching { (response.body() as? ResponseBody)?.close() }
    }

    private suspend fun persistAuthenticatedRecord(record: StoredAuthRecord) {
        // A failed commit can change SharedPreferences' memory map. Publish an
        // owner only after its complete credential record is durably accepted.
        lastPersistedAuthRecord = null
        applyAuthTokenMutation(
            updateRuntimeAuth = { authInterceptor.setToken(record.token, record.serverUrl.orEmpty()) },
            recordMutation = { recordAuthMutation() },
            persistSecureStorage = { settingsManager.saveAuthToken(record.token, record.serverUrl.orEmpty(), record.accountId) },
        )
        lastPersistedAuthRecord = record
    }

    // ─── Auth ────────────────────────────────────────────────────────────

    suspend fun login(serverUrl: String, username: String, password: String): CredentialLoginResult {
        return withContext(Dispatchers.IO) {
            authMutationMutex.withLock {
                beginCommittedAuthMutation()
                // Snapshot so a failed attempt can restore the PREVIOUS server/
                // username instead of leaving settings pointing at the new,
                // never-authenticated server. Without this, validateRetainedSession
                // would validate the old token against the new server and a 401
                // there would wipe a token still valid for the original server.
                val previousSettings = settingsManager.currentSettings
                val restorePreviousAuth = authInterceptor.restorePoint()
                var previousToken: String? = null
                var previousRecord: StoredAuthRecord? = null
                var previousTokenServerUrl = previousSettings.serverUrl
                var attemptedToken: String? = null
                suspend fun restorePreviousSettings() {
                    settingsManager.saveSettings(previousSettings)
                    attemptedToken?.let {
                        check(settingsManager.replaceAuthTokenIfCurrent(it, previousToken, previousTokenServerUrl, previousRecord?.accountId)) {
                            "Auth session changed during password rollback"
                        }
                    }
                    lastPersistedAuthRecord = previousRecord
                }
                try {
                    previousRecord = settingsManager.getAuthRecord()
                    previousToken = previousRecord?.token
                    settingsManager.quarantineLegacyRemoteCacheBeforeExplicitLogin(previousRecord, previousSettings.serverUrl)
                    previousToken?.let { settingsManager.persistAuthTokenServerBinding(it, previousSettings.serverUrl) }
                    previousRecord = settingsManager.getAuthRecord()
                    previousTokenServerUrl = previousRecord?.serverUrl ?: previousSettings.serverUrl
                    val normalizedUrl = normalizeServerUrl(serverUrl)
                    val normalizedUsername = username.trim()
                    val loginRoute = ServerRoute.parse(normalizedUrl)
                        ?: throw IllegalArgumentException("Invalid server URL")

                    // Update settings with server URL first (so Retrofit uses it)
                    settingsManager.updateSettings {
                        it.copy(serverUrl = normalizedUrl, username = normalizedUsername)
                    }
                    recordAuthMutation()

                    val dispatch = RemoteDispatchTag.noBearer(
                        loginRoute,
                        settingsManager.currentRouteRevision,
                    )
                    val response = api.login(LoginRequest(normalizedUsername, password), dispatch)
                    val accepted = acceptDirectResponse(response, dispatch) {
                        isCurrentNoBearerDispatch(dispatch)
                    } ?: throw StaleRemoteRequestException()

                    if (!accepted.isSuccessful) {
                        lastError = "Login failed: ${accepted.code()} - ${accepted.errorBody()?.string()}"
                        rollbackFailedPasswordLogin(
                            restoreSettings = { restorePreviousSettings() },
                            restoreRuntimeAuth = restorePreviousAuth,
                            recordMutation = { recordAuthMutation() },
                            onRollbackFailure = { e ->
                                Log.e(TAG, "login: Failed to roll back settings after a rejected login", e)
                            },
                        )
                        return@withLock classifyLoginHttpFailure(accepted.code())
                    }

                    val loginResponse = accepted.body()
                    val token = loginResponse?.user?.token?.trim()

                    if (token.isNullOrEmpty()) {
                        lastError = "Server response did not contain authentication token"
                        rollbackFailedPasswordLogin(
                            restoreSettings = { restorePreviousSettings() },
                            restoreRuntimeAuth = restorePreviousAuth,
                            recordMutation = { recordAuthMutation() },
                            onRollbackFailure = { e ->
                                Log.e(TAG, "login: Failed to roll back settings after a rejected login", e)
                            },
                        )
                        return@withLock CredentialLoginResult.REJECTED
                    }

                    // Retain the prior full scope if secure persistence fails.
                    attemptedToken = token
                    persistAuthenticatedRecord(StoredAuthRecord(token, normalizedUrl, loginResponse?.user?.id))

                    lastError = null
                    CredentialLoginResult.SUCCESS
                } catch (e: Exception) {
                    lastError = formatConnectionError(e)
                    rollbackFailedPasswordLogin(
                        restoreSettings = { restorePreviousSettings() },
                        restoreRuntimeAuth = restorePreviousAuth,
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
                beginCommittedAuthMutation()
                val previousSettings = settingsManager.currentSettings
                var previousRecord = settingsManager.getAuthRecord()
                val previousToken = previousRecord?.token
                var previousTokenServerUrl = previousRecord?.serverUrl ?: previousSettings.serverUrl
                val normalizedToken = token.trim()
                try {
                    settingsManager.quarantineLegacyRemoteCacheBeforeExplicitLogin(previousRecord, previousSettings.serverUrl)
                    previousToken?.let { settingsManager.persistAuthTokenServerBinding(it, previousSettings.serverUrl) }
                    previousRecord = settingsManager.getAuthRecord()
                    previousTokenServerUrl = previousRecord?.serverUrl ?: previousSettings.serverUrl
                    val normalizedUrl = normalizeServerUrl(serverUrl)

                    // Set server URL so Retrofit uses it
                    settingsManager.updateSettings {
                        it.copy(serverUrl = normalizedUrl, useApiToken = true)
                    }

                    // Set token and validate it
                    persistAuthenticatedRecord(StoredAuthRecord(normalizedToken, normalizedUrl))

                    val frozen = frozenRemoteRequestLocked() ?: return@withLock false
                    when (validateTokenDetailed(
                        forceRefresh = true,
                        tokenOverride = normalizedToken,
                        frozenRequest = frozen,
                        authLockHeld = true,
                    )) {
                        TokenValidationResult.VALID -> {
                            // Only explicit token login fetches a profile here.
                            // Cold startup restores an existing owner without
                            // allocating the full progress/bookmark response.
                            val accountId = try {
                                val dispatch = RemoteDispatchTag.frozen(frozen)
                                val response = api.getMe(dispatch)
                                acceptDirectResponse(response, dispatch) {
                                    isCurrentFrozenRemoteRequestLocked(frozen)
                                }?.takeIf { it.isSuccessful }
                                    ?.body()?.id?.takeIf { it.isNotBlank() }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                null
                            }
                            if (accountId != null) {
                                lastPersistedAuthRecord = null
                                settingsManager.saveAuthToken(normalizedToken, normalizedUrl, accountId)
                                lastPersistedAuthRecord = StoredAuthRecord(normalizedToken, normalizedUrl, accountId)
                            }
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
                                lastPersistedAuthRecord = null
                                rollbackFailedTokenLogin(
                                    previousToken = previousToken,
                                    attemptedToken = normalizedToken,
                                    readStoredToken = { settingsManager.getAuthToken() },
                                    replaceStoredToken = { expected, replacement ->
                                        check(settingsManager.replaceAuthTokenIfCurrent(expected, replacement, previousTokenServerUrl, previousRecord?.accountId)) {
                                            "Auth session changed during token rollback"
                                        }
                                    },
                                    restorePreviousSettings = { settingsManager.saveSettings(previousSettings) },
                                    restoreRuntimeAuth = { authInterceptor.setToken(it, previousTokenServerUrl) },
                                    recordMutation = { recordAuthMutation() },
                                ).also { if (it) lastPersistedAuthRecord = previousRecord }
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
                        lastPersistedAuthRecord = null
                        rollbackFailedTokenLogin(
                            previousToken = previousToken,
                            attemptedToken = normalizedToken,
                            readStoredToken = { settingsManager.getAuthToken() },
                            replaceStoredToken = { expected, replacement ->
                                check(settingsManager.replaceAuthTokenIfCurrent(expected, replacement, previousTokenServerUrl, previousRecord?.accountId)) {
                                    "Auth session changed during token rollback"
                                }
                            },
                            restorePreviousSettings = { settingsManager.saveSettings(previousSettings) },
                            restoreRuntimeAuth = { authInterceptor.setToken(it, previousTokenServerUrl) },
                            recordMutation = { recordAuthMutation() },
                        ).also { if (it) lastPersistedAuthRecord = previousRecord }
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
            beginCommittedAuthMutation()
            applyAuthTokenMutation(
                updateRuntimeAuth = { authInterceptor.setToken(null, "") },
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

    /**
     * Captures the live session identity right now, with no HTTP validation.
     * A confirmed disconnect calls this before its resetNowPlaying() step can
     * await pending terminal progress, so whatever a different ApiService
     * caller (a login from a freshly opened Settings screen, its own ViewModel
     * instance and generation counter) does during that wait is compared
     * against THIS snapshot rather than silently overwritten by it.
     */
    internal suspend fun currentAuthSession(): AuthSessionIdentity? =
        authMutationMutex.withLock {
            val token = settingsManager.getAuthToken()?.takeIf { it.isNotEmpty() }
                ?: return@withLock null
            currentAuthSessionLocked(token)
        }

    /** Clears an invalid session only if no newer auth mutation replaced it. */
    internal suspend fun logoutIfCurrentSession(expected: AuthSessionIdentity): Boolean =
        authMutationMutex.withLock {
            if (!authSessionMatches(expected, currentAuthSessionLocked(settingsManager.getAuthToken()))) {
                return@withLock false
            }
            beginCommittedAuthMutation()
            applyAuthTokenMutation(
                updateRuntimeAuth = { authInterceptor.setToken(null, "") },
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
        lastValidatedSession = null
        lastValidationAtMs = 0L
        lastValidationResult = null
    }

    /** Validation verdicts belong to one exact auth generation and server. */
    private fun recordAuthMutation() {
        synchronized(committedScopeMonitor) {
            committedAuthFence++
            authGeneration++
            scopeGeneration.value++
            authInterceptor.updateGeneration(authGeneration)
            if (!authInterceptor.hasToken()) lastPersistedAuthRecord = null
            clearValidationCache()
        }
    }

    /** Must run immediately after an auth mutation acquires authMutationMutex. */
    private fun beginCommittedAuthMutation() {
        synchronized(committedScopeMonitor) {
            committedAuthFence++
            committedScopeMonitorObserverForTest?.invoke()
        }
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
    internal suspend fun validateTokenDetailed(
        forceRefresh: Boolean = false,
        tokenOverride: String? = null,
        frozenRequest: FrozenRemoteRequest? = null,
        authLockHeld: Boolean = false,
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

                val frozen = when {
                    frozenRequest != null -> frozenRequest
                    authLockHeld -> frozenRemoteRequestLocked()
                    else -> captureFrozenRemoteRequest()
                } ?: return@withContext TokenValidationResult.UNREACHABLE
                if (frozen.bearer.token != token) return@withContext TokenValidationResult.UNREACHABLE
                val session = AuthSessionIdentity(frozen.bearer.authGeneration, token, frozen.route.url)
                suspend fun isCurrent(): Boolean = when {
                    authLockHeld -> isCurrentFrozenRemoteRequestLocked(frozen)
                    else -> isCurrentFrozenRemoteRequestSnapshot(frozen)
                }
                tokenValidationMutex.withLock {
                    if (!isCurrent()) return@withContext TokenValidationResult.UNREACHABLE
                    getCachedValidation(session, forceRefresh)?.let { return@withContext it }

                    val result = validateTokenWithLightweightEndpoint(frozen, ::isCurrent)
                    if (!isCurrent()) return@withContext TokenValidationResult.UNREACHABLE
                    cacheValidation(session, result)
                    result
                }
            } catch (e: Exception) {
                // Network/transport error — unreachable, not unauthorized.
                TokenValidationResult.UNREACHABLE
            }
        }
    }

    private suspend fun validateTokenWithLightweightEndpoint(
        frozen: FrozenRemoteRequest,
        isCurrent: suspend () -> Boolean,
    ): TokenValidationResult {
        val dispatch = RemoteDispatchTag.frozen(frozen)
        val response = try {
            api.authorize(dispatch)
        } catch (e: Exception) {
            return TokenValidationResult.UNREACHABLE
        }
        val accepted = acceptDirectResponse(response, dispatch, isCurrent)
            ?: return TokenValidationResult.UNREACHABLE

        // Some Audiobookshelf servers may not expose /api/authorize.
        // Fall back to /api/me (heavier payload), but cached/debounced above.
        if (accepted.code() == 404 || accepted.code() == 405) {
            runCatching { accepted.errorBody()?.close() }
            return fallbackValidateTokenViaProfileSync(frozen, isCurrent)
        }

        return classifyValidationStatus(accepted.code()).also {
            runCatching { accepted.errorBody()?.close() }
        }
    }

    private suspend fun fallbackValidateTokenViaProfileSync(
        frozen: FrozenRemoteRequest,
        isCurrent: suspend () -> Boolean,
    ): TokenValidationResult {
        val dispatch = RemoteDispatchTag.frozen(frozen)
        val response = try {
            api.getMe(dispatch)
        } catch (e: Exception) {
            return TokenValidationResult.UNREACHABLE
        }
        val accepted = acceptDirectResponse(response, dispatch, isCurrent)
            ?: return TokenValidationResult.UNREACHABLE
        return classifyValidationStatus(accepted.code()).also {
            runCatching { accepted.errorBody()?.close() }
        }
    }

    private fun getCachedValidation(session: AuthSessionIdentity, forceRefresh: Boolean): TokenValidationResult? {
        if (forceRefresh) return null
        val cachedResult = lastValidationResult ?: return null
        val isSameSession = session == lastValidatedSession
        val isFresh = (System.currentTimeMillis() - lastValidationAtMs) < TOKEN_VALIDATION_DEBOUNCE_MS
        return if (isSameSession && isFresh) cachedResult else null
    }

    private fun cacheValidation(session: AuthSessionIdentity, result: TokenValidationResult) {
        // Only cache definitive verdicts. UNREACHABLE is transient: caching it
        // would make a forced foreground reachability check return the stale
        // "unreachable" answer for up to the debounce window even after the
        // server comes back, delaying recovery.
        if (result == TokenValidationResult.UNREACHABLE) return
        lastValidatedSession = session
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
                beginCommittedAuthMutation()
                settingsManager.loadSettings()
                // A corrupt token entry must degrade to logged-out networking,
                // not crash the app scope or the media service that got here
                // first. loadSettings already published hasAuthToken = false
                // for this case.
                // Cancellation must escape so AuthReadiness stays retryable;
                // a swallowed CancellationException here would latch the gate
                // with a null token and poison every later validation.
                var tokenReadable = true
                var tokenServerUrl = ""
                var restoredRecord: StoredAuthRecord? = null
                val token = try {
                    settingsManager.getAuthRecord()?.token?.also { restored ->
                        var boundUrl = settingsManager.getAuthTokenServerUrl()
                        if (boundUrl == null) {
                            // Existing installations trust their previously saved
                            // session pair. Bind once before any future URL change.
                            settingsManager.persistAuthTokenServerBinding(restored, settingsManager.currentSettings.serverUrl)
                            boundUrl = settingsManager.getAuthTokenServerUrl()
                        }
                        tokenServerUrl = boundUrl ?: ""
                        restoredRecord = settingsManager.getAuthRecord()
                        restoredRecord?.let {
                            settingsManager.seedLegacyRemoteCacheForRestoredRecord(
                                it,
                                settingsManager.currentSettings.serverUrl,
                            )
                        }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (e: Exception) {
                    Log.e(TAG, "initializeFromSettings: Auth token unreadable, starting logged out", e)
                    tokenReadable = false
                    null
                }
                authInterceptor.setToken(token, tokenServerUrl)
                recordAuthMutation()
                lastPersistedAuthRecord = restoredRecord?.takeIf {
                    tokenReadable && it.token == token && it.serverUrl == tokenServerUrl
                }
                // Latch readiness only when the restore ran against healthy
                // storage. A degraded run must stay retryable so the token
                // reaches the interceptor once storage recovers.
                tokenReadable && !settingsManager.storageUnavailable.value
            }
        }
    }

    // ─── Libraries ───────────────────────────────────────────────────────

    /**
     * The library list. Returns [RemoteResult] rather than a list because an
     * empty list used to mean four different things (HTTP error, empty body,
     * thrown exception, genuinely no libraries) and a user reporting "my shelf
     * is empty" could not be told which one they hit.
     */
    internal suspend fun getLibraries(scope: ActiveRemoteScope): RemoteResult<List<Library>> = withContext(Dispatchers.IO) {
        // remoteResultCatching lets CancellationException escape uncaught
        // (see its kdoc). A plain `catch (e: Exception)` here would turn a
        // stopped sync into a persisted failure instead of a silently
        // cancelled request.
        remoteResultCatching(onFailure = { Log.w(TAG, "getLibraries failed", it) }) {
            dispatchActiveRemoteScope(scope, { api.getLibraries(it) }) { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "getLibraries: HTTP ${response.code()}")
                    RemoteResult.Failed("HTTP ${response.code()}")
                } else {
                    val body = response.body()
                    if (body == null) {
                        Log.w(TAG, "getLibraries: successful response with no body")
                        RemoteResult.Failed("empty body")
                    } else {
                        RemoteResult.Ok(
                            body.libraries.map { apiLib ->
                                namespaceIncomingLibrary(scope, Library(
                                    id = apiLib.id,
                                    name = apiLib.name,
                                    displayOrder = apiLib.displayOrder,
                                    icon = apiLib.icon ?: "audiobook",
                                    mediaType = apiLib.mediaType ?: "book",
                                    folders = apiLib.folders?.map { f ->
                                        Folder(id = f.id, fullPath = f.fullPath, libraryId = apiLib.id)
                                    } ?: emptyList()
                                ))
                            }
                        )
                    }
                }
            } ?: RemoteResult.Failed("Auth session changed")
        }
    }

    // ─── Library Items (Paginated batch load) ────────────────────────────

    /**
     * Every item in a library, paginated. A page that fails part-way through
     * yields [RemoteResult.Partial]: the books already fetched are still worth
     * showing, but the caller has to know the shelf stopped short rather than
     * ended. The same is true when a page merely comes back empty or shorter
     * than [limit] while the server's own reported total says more exist —
     * [runPaginatedFetch] is what decides that (issue #14, PR #30 review,
     * finding B); only actually reaching the reported total is a genuine Ok.
     */
    internal suspend fun getLibraryItems(
        scope: ActiveRemoteScope,
        libraryId: String,
        limit: Int = 100,
    ): RemoteResult<List<AudioBook>> =
        withContext(Dispatchers.IO) {
            val rawLibraryId = catalogEgressId(scope, libraryId)
                ?: return@withContext RemoteResult.Failed("Catalog ID is not active")
            val result = runPaginatedFetch(
                limit = limit,
                onPageFailure = { page, e -> Log.w(TAG, "getLibraryItems($libraryId) failed at page $page", e) },
            ) { page ->
                dispatchActiveRemoteScope(scope, { tag -> api.getLibraryItems(rawLibraryId, limit, page, dispatch = tag) }) { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "getLibraryItems($libraryId): HTTP ${response.code()} at page $page")
                        PageOutcome.Stopped("page $page: HTTP ${response.code()}")
                    } else {
                        val body = response.body()
                        if (body == null) {
                            Log.w(TAG, "getLibraryItems($libraryId): no body at page $page")
                            PageOutcome.Stopped("page $page: empty body")
                        } else {
                            PageOutcome.Page(
                                body.results.map { item ->
                                    namespaceIncomingBook(
                                        scope,
                                        mapToAudioBook(item, rawLibraryId, scope.frozenRequest.route.url),
                                        rawLibraryId,
                                    )
                                },
                                body.total,
                            )
                        }
                    }
                } ?: PageOutcome.Stopped("auth session changed")
            }
            if (isCurrentActiveRemoteScope(scope)) result else RemoteResult.Failed("Auth session changed")
        }

    // ─── Single Item ─────────────────────────────────────────────────────

    /** Remote reads require the lifetime scope selected by the caller. */
    suspend fun getAudioBook(itemId: String): AudioBook? = null

    internal suspend fun getAudioBook(scope: ActiveRemoteScope, itemId: String): AudioBook? = withContext(Dispatchers.IO) {
        try {
            val rawItemId = catalogEgressId(scope, itemId) ?: return@withContext null
            dispatchActiveRemoteScope(scope, { api.getItem(rawItemId, dispatch = it) }) { response ->
                if (!response.isSuccessful) null else response.body()?.let { item ->
                    namespaceIncomingBook(
                        scope,
                        mapToAudioBook(item, serverUrl = scope.frozenRequest.route.url),
                        item.libraryId,
                    )
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    // ─── Playback Session ────────────────────────────────────────────────

    suspend fun startPlaybackSession(itemId: String): PlaybackSessionInfo? = null

    internal suspend fun startPlaybackSession(scope: ActiveRemoteScope, itemId: String): PlaybackSessionInfo? =
        withContext(Dispatchers.IO) {
            val rawItemId = scope.decodeForEgress(itemId) ?: return@withContext null
            try {
                val request = StartPlaybackRequest(
                    deviceInfo = DeviceInfo(clientName = "NineLivesAudio", deviceId = settingsManager.getDeviceId()),
                )
                dispatchActiveRemoteScope(scope, { api.startPlaybackSession(rawItemId, request, it) }) { response ->
                    if (!response.isSuccessful) null else response.body()?.let { session ->
                        PlaybackSessionInfo(
                            id = session.id, itemId = scope.encodeIncoming(session.libraryItemId), episodeId = session.episodeId,
                            currentTime = session.currentTime, duration = session.duration, mediaType = session.mediaType ?: "book",
                            audioTracks = session.audioTracks?.map { track ->
                                val contentUrl = if (track.contentUrl.startsWith("http", ignoreCase = true)) track.contentUrl else {
                                    val path = if (track.contentUrl.startsWith("/")) track.contentUrl else "/${track.contentUrl}"
                                    "${scope.frozenRequest.route.url}$path"
                                }
                                AudioStreamInfo(
                                    index = track.index,
                                    codec = track.codec ?: "mp3",
                                    title = track.title,
                                    duration = track.duration,
                                    contentUrl = contentUrl,
                                )
                            } ?: emptyList(),
                            chapters = session.chapters?.map { chapter ->
                                Chapter(id = chapter.id, start = chapter.start, end = chapter.end, title = chapter.title)
                            } ?: emptyList(),
                        )
                    }
                }
            } catch (_: Exception) { null }
        }

    suspend fun syncSessionProgress(
        sessionId: String,
        currentTime: Double,
        duration: Double,
        timeListened: Double = 0.0,
    ): Boolean = false

    internal suspend fun syncSessionProgress(
        scope: ActiveRemoteScope, sessionId: String, currentTime: Double, duration: Double, timeListened: Double = 0.0,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            dispatchActiveRemoteScope(scope, {
                api.syncSessionProgress(sessionId, SyncSessionRequest(currentTime, duration, timeListened), it)
            }) { it.isSuccessful } ?: false
        } catch (_: Exception) { false }
    }

    suspend fun closeSession(sessionId: String) = Unit

    internal suspend fun closeSession(scope: ActiveRemoteScope, sessionId: String) {
        try {
            dispatchActiveRemoteScope(scope, { api.closeSession(sessionId, dispatch = it) }) { Unit }
        } catch (_: Exception) {}
    }

    // ─── Progress ────────────────────────────────────────────────────────

    suspend fun updateProgress(
        itemId: String,
        currentTime: Double,
        isFinished: Boolean = false,
        duration: Double = 0.0,
    ): Boolean = false

    internal suspend fun updateProgress(
        scope: ActiveRemoteScope, itemId: String, currentTime: Double, isFinished: Boolean = false, duration: Double = 0.0,
    ): Boolean = withContext(Dispatchers.IO) {
        val rawItemId = scope.decodeForEgress(itemId) ?: return@withContext false
        val safeTime = currentTime.coerceAtLeast(0.0)
        val progress = if (isFinished) 1.0 else if (duration > 0) (safeTime / duration).coerceIn(0.0, 1.0) else 0.0
        try {
            dispatchActiveRemoteScope(scope, {
                api.updateProgress(rawItemId, UpdateProgressRequest(safeTime, isFinished, progress), it)
            }) { it.isSuccessful } ?: false
        } catch (_: Exception) { false }
    }

    suspend fun getUserProgress(itemId: String): UserProgress? = null

    internal suspend fun getUserProgress(scope: ActiveRemoteScope, itemId: String): UserProgress? = withContext(Dispatchers.IO) {
        val rawItemId = scope.decodeForEgress(itemId) ?: return@withContext null
        try {
            dispatchActiveRemoteScope(scope, { api.getUserProgress(rawItemId, it) }) { response ->
                response.body()?.takeIf { response.isSuccessful }?.let { p ->
                    UserProgress(
                        libraryItemId = scope.encodeIncoming(p.libraryItemId),
                        currentTime = p.currentTime.seconds,
                        progress = normalizeProgress(p.progress),
                        isFinished = p.isFinished,
                        lastUpdate = if (p.lastUpdate > 0) p.lastUpdate else null,
                    )
                }
            }
        } catch (_: Exception) { null }
    }

    suspend fun getAllUserProgress(): List<UserProgress> = emptyList()

    internal suspend fun getAllUserProgress(scope: ActiveRemoteScope): List<UserProgress> = withContext(Dispatchers.IO) {
        try {
            dispatchActiveRemoteScope(scope, { api.getMe(it) }) { response ->
                response.body()?.mediaProgress.orEmpty().filter { it.libraryItemId.isNotBlank() }.map { p ->
                    UserProgress(
                        libraryItemId = scope.encodeIncoming(p.libraryItemId),
                        currentTime = p.currentTime.seconds,
                        progress = normalizeProgress(p.progress),
                        isFinished = p.isFinished,
                        lastUpdate = if (p.lastUpdate > 0) p.lastUpdate else null,
                    )
                }
            } ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    // ─── Listening Sessions ──────────────────────────────────────────────

    suspend fun getListeningSessions(
        libraryItemId: String,
        itemsPerPage: Int = LISTENING_SESSIONS_PAGE_SIZE,
    ): RemoteResult<List<ListeningSession>> =
        getAllListeningSessions(itemsPerPage).map { sessions ->
            // The supported endpoint has no verified item filter. Retain the
            // complete server result first so its global total remains valid,
            // then scope this caller's rows locally.
            sessions.filter { it.libraryItemId == libraryItemId }
        }

    /** Fetch every listening-session page across all books for the Dossier. */
    suspend fun getAllListeningSessions(
        itemsPerPage: Int = LISTENING_SESSIONS_PAGE_SIZE,
    ): RemoteResult<List<ListeningSession>> = withContext(Dispatchers.IO) {
        remoteResultCatching(onFailure = { error ->
            Log.w(TAG, "getListeningSessions failed", error)
            lastError = "Failed to load listening sessions: ${error.message}"
        }) {
            val scope = captureActiveRemoteScope()
                ?: return@remoteResultCatching RemoteResult.Failed("auth session changed")
            val frozen = scope.frozenRequest
            val result = runPaginatedFetch(
                limit = itemsPerPage,
                itemKey = { it.id },
                onPageFailure = { page, error ->
                    Log.w(TAG, "getListeningSessions failed at page $page", error)
                },
            ) { page ->
                dispatchFrozen(
                    frozen,
                    { tag -> api.getListeningSessions(itemsPerPage = itemsPerPage, page = page, dispatch = tag) },
                ) { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "getListeningSessions: HTTP ${response.code()} at page $page")
                        PageOutcome.Stopped("page $page: HTTP ${response.code()}")
                    } else {
                        val body = response.body()
                        if (body == null) {
                            Log.w(TAG, "getListeningSessions: empty body at page $page")
                            PageOutcome.Stopped("page $page: empty body")
                        } else {
                            PageOutcome.Page(
                                results = body.sessions.map {
                                    namespaceIncomingListeningSession(scope.ownerKey, it)
                                },
                                total = body.total,
                                reportedPage = body.page.takeIf { body.numPages > 0 },
                                reportedPageCount = body.numPages.takeIf { it > 0 },
                            )
                        }
                    }
                } ?: PageOutcome.Stopped("page $page: auth session changed")
            }
            if (isCurrentActiveRemoteScope(scope)) {
                result.map { sessions -> sessions.sortedByDescending { it.startedAt } }
            } else {
                RemoteResult.Failed("auth session changed")
            }
        }
    }

    // ─── Bookmarks ───────────────────────────────────────────────────────

    suspend fun getBookmarks(itemId: String): List<Bookmark> = withContext(Dispatchers.IO) {
        try {
            val frozen = captureFrozenRemoteRequest() ?: return@withContext emptyList()
            dispatchFrozen(frozen, { api.getMe(it) }) { response ->
            if (!response.isSuccessful) return@dispatchFrozen emptyList()
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
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun createBookmark(itemId: String, title: String, time: Double): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val frozen = captureFrozenRemoteRequest() ?: return@withContext false
                dispatchFrozen(frozen, { api.createBookmark(itemId, CreateBookmarkRequest(title, time), it) }) {
                    it.isSuccessful
                } ?: false
            } catch (e: Exception) {
                false
            }
        }

    suspend fun deleteBookmark(itemId: String, time: Double): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val frozen = captureFrozenRemoteRequest() ?: return@withContext false
                dispatchFrozen(frozen, { api.deleteBookmark(itemId, time, it) }) { it.isSuccessful } ?: false
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

    private fun mapToAudioBook(
        item: ApiLibraryItem,
        libraryId: String? = null,
        serverUrl: String = settingsManager.currentSettings.serverUrl,
    ): AudioBook {
        val metadata = item.media?.metadata
        val audioFiles = item.media?.audioFiles ?: emptyList()
        val firstSeries = metadata?.series?.firstOrNull()
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
