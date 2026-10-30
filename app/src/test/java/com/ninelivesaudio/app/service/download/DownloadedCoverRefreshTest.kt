package com.ninelivesaudio.app.service.download

import com.ninelivesaudio.app.data.local.entity.DownloadedCoverRow
import com.ninelivesaudio.app.domain.model.DownloadItem
import com.ninelivesaudio.app.domain.model.DownloadStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File

/**
 * A downloaded book keeps the cover it saved at download time, so an edit on
 * the server never reached it (#70). A sync now replaces a saved cover that
 * is older than the server's cover version, a few books at a time, never
 * inside a folder the download engine is writing and never recreating a
 * download folder that was deleted.
 */
class DownloadedCoverRefreshTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun cover(ts: Long?) = "https://abs.test/api/items/li_1/cover" + (ts?.let { "?ts=$it" } ?: "")

    private fun row(id: String, ts: Long? = 10_000, localCover: String? = "file:///d/$id/cover.jpg") =
        DownloadedCoverRow(id = id, coverPath = cover(ts), localCoverPath = localCover, localPath = "/d/$id")

    // ─── Is a saved cover stale ──────────────────────────────────────────

    @Test
    fun `a cover saved before the server edit is refreshed`() {
        assertTrue(savedCoverNeedsRefresh(cover(10_000), "file:///d/cover.jpg", 5_000))
    }

    @Test
    fun `a cover saved after the edit is kept`() {
        assertFalse(savedCoverNeedsRefresh(cover(10_000), "file:///d/cover.jpg", 9_000))
        assertFalse(savedCoverNeedsRefresh(cover(10_000), "file:///d/cover.jpg", 20_000))
        assertFalse(savedCoverNeedsRefresh(cover(10_000), "file:///d/cover.jpg", 10_000))
    }

    @Test
    fun `a cover url with no version never refreshes`() {
        assertFalse(savedCoverNeedsRefresh(cover(null), "file:///d/cover.jpg", 5_000))
        assertFalse(savedCoverNeedsRefresh(cover(null), "file:///d/cover.jpg", null))
        assertFalse(savedCoverNeedsRefresh(null, "file:///d/cover.jpg", null))
    }

    @Test
    fun `a download that saved no cover is left alone`() {
        assertFalse(savedCoverNeedsRefresh(cover(10_000), null, null))
        assertFalse(savedCoverNeedsRefresh(cover(10_000), "", null))
    }

    @Test
    fun `a missing saved cover file is fetched again`() {
        assertTrue(savedCoverNeedsRefresh(cover(10_000), "file:///d/cover.jpg", null))
    }

    // ─── Where a refresh may write ───────────────────────────────────────

    @Test
    fun `only a cover directly inside the download folder is written`() {
        assertEquals(File("/d/cover.jpg"), savedCoverFileIn("file:///d/cover.jpg", "/d"))
        assertEquals(File("/d/cover.jpg"), savedCoverFileIn("file:///d/cover.jpg", "/d/"))
        assertEquals(File("/d e/cover.jpg"), savedCoverFileIn("file:///d%20e/cover.jpg", "/d e"))
        assertNull(savedCoverFileIn("file:///elsewhere/cover.jpg", "/d"))
        assertNull(savedCoverFileIn("file:///d/sub/cover.jpg", "/d"))
        assertNull(savedCoverFileIn("file:///d/../e/cover.jpg", "/d"))
        assertNull(savedCoverFileIn("content://x/cover.jpg", "/d"))
        assertNull(savedCoverFileIn("file:///d/track01.mp3", "/d"))
        assertNull(savedCoverFileIn("file:///d/cover.jpg", null))
        assertNull(savedCoverFileIn("file:///d/cover.jpg", "content://tree/d"))
        assertNull(savedCoverFileIn(null, "/d"))
        assertNull(savedCoverFileIn("not a uri", "/d"))
    }

    @Test
    fun `the replacement lands whole and carries the server version`() {
        val dir = tempFolder.newFolder("book")
        val target = File(dir, "cover.jpg").apply { writeBytes(byteArrayOf(1, 1, 1)) }
        val fresh = ByteArray(4_096) { it.toByte() }

        assertTrue(replaceCoverFileAtomically(target, fresh, 1_730_000_000_000))

        assertArrayEquals(fresh, target.readBytes())
        assertFalse(File(dir, "cover.jpg.part").exists())
        assertEquals(listOf("cover.jpg"), dir.list()!!.toList())
        // Some file systems refuse a set modified time. Where it works it is exact.
        if (File(dir, "probe").apply { writeBytes(byteArrayOf(0)) }.setLastModified(1_730_000_000_000)) {
            assertEquals(1_730_000_000_000, target.lastModified())
        }
    }

    @Test
    fun `a refresh never recreates a deleted download folder`() {
        val gone = File(tempFolder.root, "deleted-book")
        assertFalse(replaceCoverFileAtomically(File(gone, "cover.jpg"), byteArrayOf(1, 2, 3), 5))
        assertFalse(gone.exists())
    }

    @Test
    fun `a failed write keeps the old cover`() {
        val dir = tempFolder.newFolder("book")
        val target = File(dir, "cover.jpg").apply { writeBytes(byteArrayOf(9, 9, 9)) }
        File(dir, "cover.jpg.part").mkdirs()
        File(dir, "cover.jpg.part/blocker").writeBytes(byteArrayOf(0))

        assertFalse(replaceCoverFileAtomically(target, byteArrayOf(1, 2, 3), 5))

        assertArrayEquals(byteArrayOf(9, 9, 9), target.readBytes())
    }

    // ─── A refresh pass ──────────────────────────────────────────────────

    @Test
    fun `only stale rows are fetched and at most twenty per pass`() = runBlocking {
        val rows = List(30) { row("s$it") } +
            listOf(row("fresh"), row("unversioned", ts = null), row("nocover", localCover = null))
        val modified = { r: DownloadedCoverRow -> if (r.id == "fresh") 50_000L else 1_000L }
        val fetched = mutableListOf<String>()
        val written = mutableListOf<Pair<String, Long>>()

        val replaced = refreshSavedCovers(
            rows = rows,
            savedModifiedMs = modified,
            fetchCover = { fetched += it; byteArrayOf(1) },
            replace = { r, _, version -> written += r.id to version; true },
        )

        assertEquals(20, replaced)
        assertEquals(20, fetched.toSet().size)
        assertTrue(fetched.all { it.startsWith("s") })
        assertTrue(written.all { it.second == 10_000L })
    }

    @Test
    fun `one failing fetch does not stop the rest`() = runBlocking {
        val rows = listOf(row("a"), row("boom"), row("empty"), row("nothing"), row("b"))
        val replaced = refreshSavedCovers(
            rows = rows,
            savedModifiedMs = { null },
            fetchCover = {
                when (it) {
                    "boom" -> throw java.io.IOException("http 500")
                    "empty" -> ByteArray(0)
                    "nothing" -> null
                    else -> byteArrayOf(1)
                }
            },
            replace = { r, _, _ -> if (r.id == "b") error("disk full") else true },
        )
        assertEquals(1, replaced)
    }

    @Test
    fun `cancellation is not swallowed`() = runBlocking {
        try {
            refreshSavedCovers(
                rows = listOf(row("a"), row("b")),
                savedModifiedMs = { null },
                fetchCover = { throw CancellationException("stop") },
                replace = { _, _, _ -> true },
            )
            fail("cancellation was swallowed")
        } catch (expected: CancellationException) {
            assertEquals("stop", expected.message)
        }
    }

    // ─── The engine's folder ─────────────────────────────────────────────

    private suspend fun DownloadOwnership.claimBook(audioBookId: String) {
        var row: DownloadItem? = DownloadItem(id = "d$audioBookId", audioBookId = audioBookId, status = DownloadStatus.Queued)
        claim(
            downloadId = "d$audioBookId",
            audioBookId = audioBookId,
            pickFolder = { File(tempFolder.root, audioBookId) },
            readRow = { row },
            writeRow = { row = it },
        )
    }

    @Test
    fun `a cover refresh skips the book the engine is writing`() = runBlocking {
        val ownership = DownloadOwnership()
        ownership.claimBook("X")
        var ran = false

        assertNull(ownership.whenEngineIsNotOn("X", File(tempFolder.root, "elsewhere")) { ran = true; true })
        assertFalse(ran)
    }

    @Test
    fun `a cover refresh runs for any other book`() = runBlocking {
        val ownership = DownloadOwnership()
        ownership.claimBook("X")

        assertEquals("Y done", ownership.whenEngineIsNotOn("Y", File(tempFolder.root, "Y")) { "Y done" })

        ownership.release()
        assertEquals("X done", ownership.whenEngineIsNotOn("X", File(tempFolder.root, "X")) { "X done" })
    }

    @Test
    fun `a cover refresh skips a folder the engine is writing for another book`() = runBlocking {
        // Two editions that started before each book got its own folder share one.
        val ownership = DownloadOwnership()
        ownership.claimBook("X")
        var ran = false

        assertNull(ownership.whenEngineIsNotOn("Y", File(tempFolder.root, "X/")) { ran = true; true })
        assertFalse(ran)
    }

    // ─── Codex round 1 ───────────────────────────────────────────────────

    @Test
    fun `persistent failures cannot starve the other stale covers`() = runBlocking {
        val rows = List(30) { row("s$it") }
        val attempted = mutableSetOf<String>()
        val random = kotlin.random.Random(7)
        repeat(6) {
            refreshSavedCovers(
                rows = rows,
                savedModifiedMs = { null },
                fetchCover = { id -> attempted += id; if (id.removePrefix("s").toInt() < 20) null else byteArrayOf(1) },
                replace = { _, _, _ -> true },
                random = random,
            )
        }
        assertTrue("later rows never tried: $attempted", (20 until 30).any { "s$it" in attempted })
    }

    @Test
    fun `a cover downloaded again during the fetch is not overwritten`() {
        val snapshot = row("a", ts = 10_000)
        // Deleted and downloaded again while the old fetch ran: same paths, a fresh file.
        assertFalse(shouldWriteRefreshedCover(snapshot, snapshot, currentSavedModifiedMs = 50_000))
        // The server cover moved on again: the fetched bytes are already old.
        assertFalse(shouldWriteRefreshedCover(snapshot, row("a", ts = 20_000), currentSavedModifiedMs = 5_000))
        // The download is gone or moved.
        assertFalse(shouldWriteRefreshedCover(snapshot, null, currentSavedModifiedMs = 5_000))
        assertFalse(shouldWriteRefreshedCover(snapshot, snapshot.copy(localPath = "/elsewhere"), currentSavedModifiedMs = 5_000))
        // Nothing changed: write.
        assertTrue(shouldWriteRefreshedCover(snapshot, snapshot, currentSavedModifiedMs = 5_000))
        assertTrue(shouldWriteRefreshedCover(snapshot, snapshot, currentSavedModifiedMs = null))
    }

    private fun clientAnswering(contentType: String, body: ByteArray, seen: (okhttp3.Call) -> Unit = {}) =
        okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            seen(chain.call())
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("fixture")
                .body(body.toResponseBody(contentType.toMediaType())).build()
        }.build()

    private val jpegish = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2)
    private val looksLikeJpeg: (ByteArray) -> Boolean = { it.size >= 2 && it[0] == 0xFF.toByte() && it[1] == 0xD8.toByte() }

    @Test
    fun `an image comes back whole`() = runBlocking {
        val bytes = fetchSavedCoverBytes(clientAnswering("image/jpeg", jpegish), "https://abs.test/api/items/a/cover?ts=5", 5_000, looksLikeJpeg)
        assertArrayEquals(jpegish, bytes)
    }

    @Test
    fun `an html page answered with 200 never replaces a cover`() = runBlocking {
        val html = "<html>sign in</html>".toByteArray()
        assertNull(fetchSavedCoverBytes(clientAnswering("text/html", html), "https://abs.test/api/items/a/cover", 5_000, looksLikeJpeg))
        // A proxy that lies about the type is caught by the decode check.
        assertNull(fetchSavedCoverBytes(clientAnswering("image/jpeg", html), "https://abs.test/api/items/a/cover", 5_000, looksLikeJpeg))
    }

    @Test
    fun `each cover fetch carries what is left of the deadline`() = runBlocking {
        var timeoutNanos = 0L
        fetchSavedCoverBytes(
            clientAnswering("image/jpeg", jpegish) { timeoutNanos = it.timeout().timeoutNanos() },
            "https://abs.test/api/items/a/cover", 1_234, looksLikeJpeg,
        )
        assertEquals(1_234_000_000L, timeoutNanos)
    }

    @Test
    fun `a cancelled refresh cancels the request in flight`() = runBlocking {
        var cancelled = false
        val stuck = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val until = System.currentTimeMillis() + 10_000
            while (!chain.call().isCanceled() && System.currentTimeMillis() < until) Thread.sleep(10)
            cancelled = chain.call().isCanceled()
            throw java.io.IOException("Canceled")
        }.build()
        val started = System.currentTimeMillis()

        val result = kotlinx.coroutines.withTimeoutOrNull(200) {
            fetchSavedCoverBytes(stuck, "https://abs.test/api/items/a/cover", 60_000, looksLikeJpeg)
        }

        assertNull(result)
        assertTrue("took ${System.currentTimeMillis() - started} ms", System.currentTimeMillis() - started < 3_000)
        val deadline = System.currentTimeMillis() + 3_000
        while (!cancelled && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(cancelled)
    }

    @Test
    fun `a non http cover address is never fetched`() = runBlocking {
        var asked = false
        val client = clientAnswering("image/jpeg", jpegish) { asked = true }
        assertNull(fetchSavedCoverBytes(client, "file:///d/cover.jpg", 5_000, looksLikeJpeg))
        assertFalse(asked)
    }

    @Test
    fun `a cover two editions share is left alone`() {
        // Two editions downloaded before each book got its own folder share one cover.jpg.
        val a = DownloadedCoverRow("a", cover(10_000), "file:///d/shared/cover.jpg", "/d/shared")
        val b = DownloadedCoverRow("b", cover(20_000), "file:///d/shared/cover.jpg", "/d/shared/")
        val own = DownloadedCoverRow("c", cover(10_000), "file:///d/c/cover.jpg", "/d/c")
        val nowhere = DownloadedCoverRow("x", cover(10_000), "content://x/cover.jpg", "/d/x")

        assertEquals(listOf("c"), rowsWithTheirOwnCover(listOf(a, b, own, nowhere)).map { it.id })
    }
}
