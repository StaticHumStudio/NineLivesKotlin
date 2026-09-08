package com.ninelivesaudio.app.data.remote

import com.ninelivesaudio.app.service.SettingsManager
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

internal data class ServerOrigin(val scheme: String, val host: String, val port: Int) {
    companion object {
        fun from(url: HttpUrl) = ServerOrigin(url.scheme, url.host, url.port)
        fun parse(url: String): ServerOrigin? = url.toHttpUrlOrNull()?.let(::from)
    }
}

/** Auth is checked for each network exchange, including redirected media requests. */
@Singleton
class AuthInterceptor private constructor(
    private val selectedRoute: () -> ServerRoute?,
    private val selectedRouteRevision: () -> Long?,
    private val enforceSelectedRoute: Boolean,
) : Interceptor {
    @Inject constructor(settingsManager: SettingsManager) : this({
        ServerRoute.parse(settingsManager.currentSettings.serverUrl)
    }, { settingsManager.currentRouteRevision }, true)

    /** Test-only constructor. Production evaluates the selected settings route on every exchange. */
    internal constructor() : this({ null }, { null }, false)

    /** Test-only constructor for a live settings-route mismatch. */
    internal constructor(selectedRoute: () -> ServerRoute?) : this(selectedRoute, { null }, true)

    /** Test-only constructor for a route revision transition. */
    internal constructor(
        selectedRoute: () -> ServerRoute?,
        selectedRouteRevision: () -> Long,
    ) : this(selectedRoute, { selectedRouteRevision() }, true)
    private data class Scope(
        val token: String,
        val route: ServerRoute,
        val authGeneration: Long,
    )
    private class InjectedAuth(val scope: Scope)
    @Volatile private var scope: Scope? = null

    fun setToken(token: String?, serverUrl: String) {
        scope = token?.takeIf { it.isNotEmpty() }?.let { value ->
            ServerRoute.parse(serverUrl)?.let { Scope(value, it, scope?.authGeneration ?: 0L) }
        }
    }

    /** Bump a retained bearer whenever ApiService replaces its auth session. */
    internal fun updateGeneration(authGeneration: Long) {
        scope = scope?.copy(authGeneration = authGeneration)
    }

    internal fun restorePoint(): () -> Unit {
        val captured = scope
        return { scope = captured }
    }

    fun hasToken(): Boolean = scope != null

    fun hasTokenFor(serverUrl: String): Boolean =
        scope?.route?.let { it == ServerRoute.parse(serverUrl) } == true

    internal fun matches(token: String, serverUrl: String): Boolean =
        scope?.let { it.token == token && it.route == ServerRoute.parse(serverUrl) } == true

    internal fun captureFrozenBearer(
        token: String,
        route: ServerRoute,
        authGeneration: Long,
    ): FrozenBearer? = scope?.takeIf {
        it.token == token && it.route == route && it.authGeneration == authGeneration
    }?.let { FrozenBearer(it.token, it.route, it.authGeneration) }

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        val current = scope
        val dispatch = request.tag(RemoteDispatchTag::class.java)
        val configuredRoute = selectedRoute()
        if (dispatch != null && enforceSelectedRoute && configuredRoute != dispatch.route) {
            throw StaleRemoteRequestException()
        }
        if (dispatch?.routeRevision != null && enforceSelectedRoute &&
            selectedRouteRevision() != dispatch.routeRevision) {
            throw StaleRemoteRequestException()
        }
        val injected = request.tag(InjectedAuth::class.java)
        if (injected != null) {
            val builder = request.newBuilder().tag(InjectedAuth::class.java, null)
            // Remove only our own stale header. An explicit caller replacement
            // keeps its original contract and is never overwritten.
            if (request.header("Authorization") == "Bearer ${injected.scope.token}" &&
                (dispatch?.bearer == null && dispatch != null ||
                    injected.scope != current || !injected.scope.route.contains(request.url) ||
                    enforceSelectedRoute && configuredRoute != injected.scope.route)) {
                builder.removeHeader("Authorization")
            }
            request = builder.build()
        }

        val frozenScope = dispatch?.bearer?.let {
            Scope(it.token, it.route, it.authGeneration)
        }
        if (dispatch != null && (dispatch.bearer != null && frozenScope != current)) {
            throw StaleRemoteRequestException()
        }

        val injectionScope = when {
            dispatch?.bearer == null && dispatch != null -> null
            frozenScope != null && frozenScope.route.contains(request.url) -> frozenScope
            frozenScope != null -> null
            current?.route?.contains(request.url) == true &&
                (!enforceSelectedRoute || configuredRoute == current.route) -> current
            else -> null
        }
        if (request.header("Authorization").isNullOrBlank() && injectionScope != null) {
            request = request.newBuilder()
                .header("Authorization", "Bearer ${injectionScope.token}")
                .tag(InjectedAuth::class.java, InjectedAuth(injectionScope))
                .build()
        } else if (injected != null && injected.scope == injectionScope &&
            request.header("Authorization") == "Bearer ${injectionScope?.token}") {
            request = request.newBuilder().tag(InjectedAuth::class.java, injected).build()
        }
        return chain.proceed(request)
    }
}
