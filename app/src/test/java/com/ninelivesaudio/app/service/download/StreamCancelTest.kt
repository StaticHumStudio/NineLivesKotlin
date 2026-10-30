package com.ninelivesaudio.app.service.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A server that stops sending mid-file leaves the engine's read blocked
 * until the read timeout, and a replacement drain waits behind it that long
 * (#86). Cancelling the engine has to end the read at once.
 */
class StreamCancelTest {

    @Test
    fun `cancelling a read stuck on a stalled server ends it at once and closes the stream`() = runBlocking {
        val stream = StallingStream()
        val job = launch(Dispatchers.IO) { closeOnCancel(stream) { stream.read() } }
        assertTrue("the read never started", stream.reading.await(2, TimeUnit.SECONDS))

        job.cancel()
        withTimeout(2_000) { job.join() }

        assertTrue(stream.closed)
        assertTrue(job.isCancelled)
    }

    @Test
    fun `a read that finishes leaves the stream open for its owner`() = runBlocking {
        val stream = StallingStream()

        val result = withContext(Dispatchers.IO) { closeOnCancel(stream) { 42 } }

        assertEquals(42, result)
        assertFalse(stream.closed)
    }

    @Test
    fun `a read that fails on its own reports its own error`() = runBlocking {
        val stream = StallingStream()

        val error = runCatching {
            withContext(Dispatchers.IO) { closeOnCancel(stream) { throw IOException("connection reset") } }
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals("connection reset", error?.message)
        assertFalse(stream.closed)
    }
    @Test
    fun `cancelling a download whose server stalls mid-file frees the read at once`() = runBlocking {
        // A real socket: the server sends the headers and a few bytes of a big
        // file, then goes quiet. Only cancelling the call frees OkHttp's read.
        ServerSocket(0).use { server ->
            val serving = Thread {
                runCatching {
                    server.accept().use { socket ->
                        socket.getInputStream().read(ByteArray(4096))
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: 1000000\r\n\r\n0123456789".toByteArray())
                            flush()
                        }
                        Thread.sleep(30_000)
                    }
                }
            }.apply { isDaemon = true; start() }
            val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
            val call = client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/file").build())
            val reading = CountDownLatch(1)

            val job = launch(Dispatchers.IO) {
                closeOnCancel(Closeable { call.cancel() }) {
                    call.execute().body!!.use { body ->
                        val input = body.byteStream()
                        val buffer = ByteArray(8192)
                        while (input.read(buffer) > 0) reading.countDown()
                    }
                }
            }
            assertTrue("the download never started", reading.await(5, TimeUnit.SECONDS))

            job.cancel()
            withTimeout(2_000) { job.join() }

            assertTrue(job.isCancelled)
            serving.interrupt()
        }
    }
}

/**
 * A stream whose read blocks like a socket on a stalled server: it waits
 * until someone closes it, then fails the way a closed socket does.
 */
internal class StallingStream : InputStream() {
    val reading = CountDownLatch(1)
    private val released = CountDownLatch(1)

    @Volatile
    var closed = false
        private set

    override fun read(): Int {
        reading.countDown()
        released.await()
        throw IOException("Socket closed")
    }

    override fun close() {
        closed = true
        released.countDown()
    }
}
