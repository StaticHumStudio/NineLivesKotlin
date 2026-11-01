package com.ninelivesaudio.app.widget

import com.ninelivesaudio.app.service.BoundedInputStream
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Downloads one widget cover. Cancelling the coroutine cancels the HTTP call,
 * and a whole-call timeout (under the warm-up deadline) stops a stuck stream
 * read. Built on a derived client, so the shared client's auth and
 * interceptors still apply while its long timeouts do not.
 */
internal class WidgetCoverDownloader(
    sharedClient: OkHttpClient,
    callTimeout: Duration = 6.seconds,
) {
    private val client = sharedClient.newBuilder().callTimeout(callTimeout.toJavaDuration()).build()

    suspend fun fetch(url: String): ByteArray = suspendCancellableCoroutine { cont ->
        val call = client.newCall(Request.Builder().url(url).build())
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (!it.isSuccessful) throw IOException("http ${it.code}")
                        val body = it.body ?: throw IOException("empty body")
                        if (body.contentLength() > MAX_DOWNLOAD_BYTES) throw IOException("cover too large")
                        cont.resume(BoundedInputStream(body.byteStream(), MAX_DOWNLOAD_BYTES).readBytes())
                    } catch (e: IOException) {
                        cont.resumeWithException(e)
                    }
                }
            }
        })
    }

    private companion object {
        const val MAX_DOWNLOAD_BYTES = 3L * 1024 * 1024
    }
}
