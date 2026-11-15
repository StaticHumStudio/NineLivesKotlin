package com.ninelivesaudio.app.data.remote

import com.ninelivesaudio.app.data.remote.dto.ApiUserProgress
import kotlinx.coroutines.runBlocking
import okhttp3.Headers.Companion.headersOf
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

/**
 * Before a queued row is sent the flush reads the book's progress (issue
 * #118). "No progress" and "could not ask" must come back apart, with the
 * server's clock from the Date header.
 */
class ServerProgressReadTest {

    private val date = "Sun, 15 Nov 2026 21:00:00 GMT"
    private val dateMs = 1_794_776_400_000L
    private val body = ApiUserProgress(
        libraryItemId = "item-1",
        currentTime = 5_631.0,
        progress = 15.6,
        isFinished = false,
        lastUpdate = 1_794_770_000_000L,
    )

    private fun read(fetch: suspend () -> Response<ApiUserProgress>) = runBlocking {
        readServerProgress(fetch, normalizeProgress = { it / 100.0 })
    }

    @Test
    fun `progress comes back with the server clock`() {
        val result = read { Response.success(body, headersOf("Date", date)) }

        val found = result as ServerProgressRead.Found
        assertEquals(dateMs, found.serverNowMs)
        assertEquals(5_631.0.seconds, found.progress.currentTime)
        assertEquals(0.156, found.progress.progress, 1e-9)
        assertEquals(1_794_770_000_000L, found.progress.lastUpdate)
    }

    @Test
    fun `no Date header gives no server clock`() {
        val result = read { Response.success(body) }

        assertNull((result as ServerProgressRead.Found).serverNowMs)
    }

    @Test
    fun `404 is no progress, not a failure`() {
        val raw = okhttp3.Response.Builder()
            .code(404)
            .message("Not Found")
            .protocol(Protocol.HTTP_1_1)
            .request(Request.Builder().url("http://localhost/api/me/progress/item-1").build())
            .header("Date", date)
            .build()

        assertEquals(
            ServerProgressRead.Missing(serverNowMs = dateMs),
            read { Response.error("".toResponseBody(), raw) },
        )
    }

    @Test
    fun `server errors and exceptions are unavailable`() {
        assertEquals(ServerProgressRead.Unavailable, read { Response.error(500, "".toResponseBody()) })
        assertEquals(ServerProgressRead.Unavailable, read { throw IOException("offline") })
        assertEquals(ServerProgressRead.Unavailable, read { Response.success<ApiUserProgress>(null) })
    }
}
