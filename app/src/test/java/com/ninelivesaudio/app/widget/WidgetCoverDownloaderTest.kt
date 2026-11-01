package com.ninelivesaudio.app.widget

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A server that accepts and never answers, like a stalled cover host. */
class WidgetCoverDownloaderTest {
    private lateinit var server: ServerSocket
    private val url get() = "http://127.0.0.1:${server.localPort}/cover.jpg"

    // Long timeouts, like the shared app client, so only our own bounds can save us.
    private val slowClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    @Before fun open() { server = ServerSocket(0) }
    @After fun close() { server.close() }

    @Test fun coroutineTimeoutStopsAStalledDownloadQuickly() {
        val downloader = WidgetCoverDownloader(slowClient, callTimeout = 30.seconds)
        val started = System.nanoTime()
        val result = runBlocking { withTimeoutOrNull(500.milliseconds) { downloader.fetch(url) } }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertNull(result)
        assertTrue("took ${tookMs}ms", tookMs < 2_000)
    }

    @Test fun callTimeoutBoundsADownloadNobodyCancels() {
        val downloader = WidgetCoverDownloader(slowClient, callTimeout = 500.milliseconds)
        val started = System.nanoTime()
        try {
            runBlocking { downloader.fetch(url) }
            fail("expected IOException")
        } catch (_: IOException) {
        }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("took ${tookMs}ms", tookMs < 3_000)
    }
}
