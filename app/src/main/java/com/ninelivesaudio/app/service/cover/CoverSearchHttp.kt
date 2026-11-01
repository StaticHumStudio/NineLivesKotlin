package com.ninelivesaudio.app.service.cover

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Cover services get the app name and a contact address (Open Library asks for it). */
internal const val COVER_SEARCH_CONTACT = "Static@StaticHum.Studio"

internal fun coverSearchUserAgent(versionName: String): String = "NineLivesAudio/$versionName ($COVER_SEARCH_CONTACT)"

internal const val MAX_SEARCH_BODY_BYTES = 2L * 1024 * 1024
internal const val MAX_THUMBNAIL_BYTES = 512L * 1024
internal const val MAX_COVER_DOWNLOAD_BYTES = 10L * 1024 * 1024
internal const val MAX_PHOTO_BYTES = 25L * 1024 * 1024

/**
 * The client for cover services. Built from the app client so the connection pool and
 * TLS setup carry over (the self signed relaxation only covers the configured server's
 * host), with every app interceptor removed: no Audiobookshelf token, base URL rewrite,
 * cover URL rewrite or debug log can reach a third party. Its own dispatcher holds
 * requests to 3 per host at once (Open Library's limit for an identified app).
 */
fun coverSearchHttpClient(appClient: OkHttpClient, userAgent: String): OkHttpClient {
    val builder = appClient.newBuilder()
    builder.interceptors().clear()
    builder.networkInterceptors().clear()
    return builder
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 3 })
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
        }
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()
}

/**
 * The whole stream, or null as soon as it passes [maxBytes]. Never a cut short image.
 * Closes [input].
 */
internal fun readCapped(input: InputStream, maxBytes: Long): ByteArray? = input.use { source ->
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    var total = 0L
    while (true) {
        val n = source.read(buffer)
        if (n < 0) break
        total += n
        if (total > maxBytes) return@use null
        out.write(buffer, 0, n)
    }
    out.toByteArray()
}

/**
 * Queues the call on the client's dispatcher (so its per host limit applies, which a
 * blocking execute would skip) and cancels it when the coroutine is cancelled.
 */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }

        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }
    })
}

/** GET [url] and return the whole body, or throw IOException on a non 2xx code or a body over [maxBytes]. */
internal suspend fun OkHttpClient.getCapped(url: String, maxBytes: Long): ByteArray {
    val response = newCall(Request.Builder().url(url).get().build()).await()
    return response.use {
        if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
        val body = it.body ?: throw IOException("no body")
        runInterruptible(Dispatchers.IO) { readCapped(body.byteStream(), maxBytes) }
            ?: throw IOException("body over $maxBytes bytes")
    }
}
