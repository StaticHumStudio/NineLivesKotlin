package com.ninelivesaudio.app.data.remote

import com.ninelivesaudio.app.data.remote.dto.ApiUserProgress
import com.ninelivesaudio.app.data.remote.dto.LocalSessionResult
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

/**
 * After the phone's listening reaches the server, the server's spot is read
 * back (issue #118). "No progress" and "could not ask" must come back apart,
 * and an offline session sync answer must account for every session sent.
 */
class ServerProgressReadTest {

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
    fun `progress comes back as found`() {
        val found = read { Response.success(body) } as ServerProgressRead.Found

        assertEquals(5_631.0.seconds, found.progress.currentTime)
        assertEquals(0.156, found.progress.progress, 1e-9)
        assertEquals(1_794_770_000_000L, found.progress.lastUpdate)
    }

    @Test
    fun `404 is no progress, not a failure`() {
        val raw = okhttp3.Response.Builder()
            .code(404)
            .message("Not Found")
            .protocol(Protocol.HTTP_1_1)
            .request(Request.Builder().url("http://localhost/api/me/progress/item-1").build())
            .build()

        assertEquals(ServerProgressRead.Missing, read { Response.error("".toResponseBody(), raw) })
    }

    @Test
    fun `server errors and exceptions are unavailable`() {
        assertEquals(ServerProgressRead.Unavailable, read { Response.error(500, "".toResponseBody()) })
        assertEquals(ServerProgressRead.Unavailable, read { throw IOException("offline") })
        assertEquals(ServerProgressRead.Unavailable, read { Response.success<ApiUserProgress>(null) })
    }

    private fun result(success: Boolean, progressSynced: Boolean = false, error: String? = null) =
        LocalSessionResult(id = "s", success = success, progressSynced = progressSynced, error = error)

    @Test
    fun `every session landing is delivered, with which spots the server took`() {
        assertEquals(
            LocalSessionSync.Delivered(listOf(true, false)),
            localSessionSyncOutcome(2, listOf(result(true, progressSynced = true), result(true))),
        )
    }

    @Test
    fun `a book the server no longer has is gone`() {
        assertEquals(
            LocalSessionSync.ItemGone,
            localSessionSyncOutcome(1, listOf(result(false, error = "Media item not found"))),
        )
    }

    @Test
    fun `an answer that does not account for every session is no answer`() {
        assertEquals(LocalSessionSync.Unavailable, localSessionSyncOutcome(2, listOf(result(true))))
        assertEquals(LocalSessionSync.Unavailable, localSessionSyncOutcome(1, null))
        assertEquals(LocalSessionSync.Unavailable, localSessionSyncOutcome(2, listOf(result(true), result(false))))
        assertEquals(LocalSessionSync.Unavailable, localSessionSyncOutcome(0, emptyList()))
    }
}
