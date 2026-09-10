package com.ninelivesaudio.app.data.remote

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
class AuthInterceptor @Inject constructor() : Interceptor {
    private data class Scope(val token: String, val origin: ServerOrigin)
    private class InjectedAuth(val scope: Scope)
    @Volatile private var scope: Scope? = null

    fun setToken(token: String?, serverUrl: String) {
        scope = token?.takeIf { it.isNotEmpty() }?.let { value ->
            ServerOrigin.parse(serverUrl)?.let { Scope(value, it) }
        }
    }

    internal fun restorePoint(): () -> Unit {
        val captured = scope
        return { scope = captured }
    }

    fun hasToken(): Boolean = scope != null

    fun hasTokenFor(serverUrl: String): Boolean =
        scope?.origin?.let { it == ServerOrigin.parse(serverUrl) } == true

    internal fun matches(token: String, serverUrl: String): Boolean =
        scope?.let { it.token == token && it.origin == ServerOrigin.parse(serverUrl) } == true

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        val current = scope
        val origin = ServerOrigin.from(request.url)
        val injected = request.tag(InjectedAuth::class.java)
        if (injected != null) {
            val builder = request.newBuilder().tag(InjectedAuth::class.java, null)
            // Remove only our own stale header. An explicit caller replacement
            // keeps its original contract and is never overwritten.
            if (request.header("Authorization") == "Bearer ${injected.scope.token}" &&
                (injected.scope != current || injected.scope.origin != origin)) {
                builder.removeHeader("Authorization")
            }
            request = builder.build()
        }
        if (request.header("Authorization").isNullOrBlank() && current?.origin == origin) {
            request = request.newBuilder()
                .header("Authorization", "Bearer ${current.token}")
                .tag(InjectedAuth::class.java, InjectedAuth(current))
                .build()
        } else if (injected != null && injected.scope == current && current.origin == origin &&
            request.header("Authorization") == "Bearer ${current.token}") {
            request = request.newBuilder().tag(InjectedAuth::class.java, injected).build()
        }
        return chain.proceed(request)
    }
}
