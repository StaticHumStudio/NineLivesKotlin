package com.ninelivesaudio.app.data.remote

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Covers load through their own request dispatcher so a burst of them never
 * holds the per-host slots that play, session and progress calls need. Auth,
 * base URL rewriting, timeouts and the connection pool all carry over.
 */
class ImageHttpClientTest {

    private val auth = Interceptor { chain -> chain.proceed(chain.request()) }
    private val baseUrl = Interceptor { chain -> chain.proceed(chain.request()) }
    private val app = OkHttpClient.Builder()
        .addInterceptor(baseUrl)
        .addNetworkInterceptor(auth)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    @Test
    fun `covers get their own dispatcher`() {
        val images = imageLoaderHttpClient(app)

        assertNotSame(app.dispatcher, images.dispatcher)
    }

    @Test
    fun `cover slots do not count against API calls`() {
        val images = imageLoaderHttpClient(app)
        images.dispatcher.maxRequestsPerHost = 1

        assertEquals(5, app.dispatcher.maxRequestsPerHost)
    }

    @Test
    fun `auth and base URL interceptors carry over`() {
        val images = imageLoaderHttpClient(app)

        assertEquals(listOf(baseUrl), images.interceptors)
        assertEquals(listOf(auth), images.networkInterceptors)
    }

    @Test
    fun `timeouts and the connection pool carry over`() {
        val images = imageLoaderHttpClient(app)

        assertEquals(app.readTimeoutMillis, images.readTimeoutMillis)
        assertSame(app.connectionPool, images.connectionPool)
    }
}
