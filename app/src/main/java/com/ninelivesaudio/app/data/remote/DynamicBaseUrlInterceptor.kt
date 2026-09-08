package com.ninelivesaudio.app.data.remote

import com.ninelivesaudio.app.service.SettingsManager
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
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
class DynamicBaseUrlInterceptor internal constructor(
    private val serverUrl: () -> String,
) : Interceptor {
    @Inject constructor(settingsManager: SettingsManager) : this({ settingsManager.currentSettings.serverUrl })

    companion object {
        /** Placeholder base URL used when building Retrofit. */
        const val PLACEHOLDER_BASE_URL = "http://localhost/"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val originalUrl = originalRequest.url

        // Only the exact Retrofit placeholder origin is eligible for routing.
        if (ServerOrigin.from(originalUrl) != ServerOrigin.parse(PLACEHOLDER_BASE_URL)) {
            return chain.proceed(originalRequest)
        }

        val newBaseUrl = validatedServerBaseUrl(serverUrl())
            ?: throw IOException("Audiobookshelf server is not configured")

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

fun validatedServerBaseUrl(serverUrl: String): HttpUrl? {
    val parsed = serverUrl.trim().trimEnd('/').toHttpUrlOrNull() ?: return null
    return parsed.takeIf { it.scheme == "http" || it.scheme == "https" }
}
