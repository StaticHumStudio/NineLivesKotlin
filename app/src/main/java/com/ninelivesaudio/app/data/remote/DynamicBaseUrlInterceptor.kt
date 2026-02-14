package com.ninelivesaudio.app.data.remote

import com.ninelivesaudio.app.service.SettingsManager
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OkHttp interceptor that rewrites the base URL of each request to
 * use the currently configured server URL from settings.
 *
 * Retrofit requires a base URL at build time, but we don't know the server URL
 * until the user logs in. This interceptor dynamically replaces the placeholder
 * base URL with the real server URL.
 */
@Singleton
class DynamicBaseUrlInterceptor @Inject constructor(
    private val settingsManager: SettingsManager,
) : Interceptor {

    companion object {
        /** Placeholder base URL used when building Retrofit. */
        const val PLACEHOLDER_BASE_URL = "http://localhost/"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val originalUrl = originalRequest.url

        // Only rewrite requests created from the Retrofit placeholder host.
        if (originalUrl.host != PLACEHOLDER_BASE_URL.toHttpUrlOrNull()?.host) {
            return chain.proceed(originalRequest)
        }

        val serverUrl = settingsManager.currentSettings.serverUrl

        if (serverUrl.isEmpty()) {
            return chain.proceed(originalRequest)
        }

        val newBaseUrl = serverUrl.trimEnd('/').toHttpUrlOrNull() ?: return chain.proceed(originalRequest)

        // Rebuild URL with server scheme/host/port and optional base path segments.
        val combinedPathSegments = buildList {
            addAll(newBaseUrl.encodedPathSegments.filter { it.isNotEmpty() })
            addAll(originalUrl.encodedPathSegments.filter { it.isNotEmpty() })
        }

        val newUrlBuilder = HttpUrl.Builder()
            .scheme(newBaseUrl.scheme)
            .host(newBaseUrl.host)
            .port(newBaseUrl.port)

        if (combinedPathSegments.isEmpty()) {
            newUrlBuilder.addPathSegment("")
        } else {
            combinedPathSegments.forEach { segment ->
                newUrlBuilder.addEncodedPathSegment(segment)
            }
        }

        originalUrl.queryParameterNames.forEach { name ->
            originalUrl.queryParameterValues(name).forEach { value ->
                newUrlBuilder.addQueryParameter(name, value)
            }
        }

        val newUrl = newUrlBuilder.build()

        val newRequest = originalRequest.newBuilder()
            .url(newUrl)
            .build()

        return chain.proceed(newRequest)
    }
}
