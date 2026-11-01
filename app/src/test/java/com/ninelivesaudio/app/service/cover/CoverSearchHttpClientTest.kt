package com.ninelivesaudio.app.service.cover

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Cover search talks to a third party, so its client must carry none of the app's
 * interceptors (no Audiobookshelf token, no base URL or cover URL rewrite, no debug log).
 */
class CoverSearchHttpClientTest {

    private val auth = Interceptor { chain -> chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer x").build()) }
    private val baseUrl = Interceptor { chain -> chain.proceed(chain.request()) }
    private val app = OkHttpClient.Builder()
        .addInterceptor(baseUrl)
        .addNetworkInterceptor(auth)
        .build()

    private fun answer(chain: Interceptor.Chain, code: Int, body: String): Response =
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x")
            .body(body.toResponseBody()).build()

    private fun clientThatAnswers(code: Int, body: String): OkHttpClient =
        coverSearchHttpClient(OkHttpClient(), "UA").newBuilder()
            .addInterceptor { chain -> answer(chain, code, body) }
            .build()

    @Test fun `the cover client carries none of the app's interceptors`() {
        val c = coverSearchHttpClient(app, "UA")
        assertEquals(1, c.interceptors.size)
        assertTrue(c.networkInterceptors.isEmpty())
    }

    @Test fun `the cover client waits in its own line, three per host`() {
        val c = coverSearchHttpClient(app, "UA")
        assertNotSame(app.dispatcher, c.dispatcher)
        assertEquals(3, c.dispatcher.maxRequestsPerHost)
    }

    @Test fun `the cover client gives up in eight seconds`() {
        val c = coverSearchHttpClient(app, "UA")
        assertEquals(8_000, c.callTimeoutMillis)
        assertEquals(5_000, c.connectTimeoutMillis)
        assertEquals(6_000, c.readTimeoutMillis)
    }

    @Test fun `every request names the app and a contact address`() {
        var seen: Request? = null
        val c = coverSearchHttpClient(app, coverSearchUserAgent("2.2.0")).newBuilder()
            .addInterceptor { chain ->
                seen = chain.request()
                answer(chain, 200, "")
            }
            .build()
        c.newCall(Request.Builder().url("https://openlibrary.org/search.json").build()).execute().close()
        assertEquals("NineLivesAudio/2.2.0 (Static@StaticHum.Studio)", seen!!.header("User-Agent"))
        assertNull(seen!!.header("Authorization"))
    }

    @Test fun `an image under the cap is read whole`() =
        assertEquals(10, readCapped(ByteArrayInputStream(ByteArray(10)), 10)!!.size)

    @Test fun `an image over the cap is refused, not cut short`() =
        assertNull(readCapped(ByteArrayInputStream(ByteArray(11)), 10))

    @Test fun `a capped get hands back the whole body`() = runBlocking {
        assertEquals("abc", String(clientThatAnswers(200, "abc").getCapped("https://covers.openlibrary.org/a", 10)))
    }

    @Test fun `a capped get over the cap fails`() = runBlocking {
        try {
            clientThatAnswers(200, "x".repeat(20)).getCapped("https://covers.openlibrary.org/a", 10)
            fail("expected IOException")
        } catch (_: IOException) {
        }
    }

    @Test fun `a search that answers 500 fails the source`() = runBlocking {
        try {
            OpenLibraryCoverSource.onCoverClient(clientThatAnswers(500, "busy")).search(CoverQuery("x", "x", null))
            fail("expected IOException")
        } catch (_: IOException) {
        }
    }

    @Test fun `a search that answers html fails the source`() = runBlocking {
        try {
            OpenLibraryCoverSource.onCoverClient(clientThatAnswers(200, "<html>")).search(CoverQuery("x", "x", null))
            fail("expected IOException")
        } catch (_: IOException) {
        }
    }

    /** A fake web: each url answers a redirect (to the given location) or a body. */
    private fun web(vararg routes: Pair<String, String>): OkHttpClient =
        coverSearchHttpClient(OkHttpClient(), "UA").newBuilder()
            .addInterceptor { chain ->
                val target = routes.toMap()[chain.request().url.toString()]
                when {
                    target == null -> answer(chain, 404, "")
                    target.startsWith("body:") -> answer(chain, 200, target.removePrefix("body:"))
                    else -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Found")
                        .header("Location", target).body("".toResponseBody()).build()
                }
            }.build()

    private val olCover = "https://covers.openlibrary.org/b/id/12216503-M.jpg?default=false"
    private val archive = "https://archive.org/download/m_covers_0012/m_covers_0012_21.zip/0012216503-M.jpg"
    private val iaNode = "https://ia600505.us.archive.org/view_archive.php?archive=/5/items/m_covers_0012/m_covers_0012_21.zip&file=0012216503-M.jpg"

    private fun refused(block: suspend () -> Unit) = runBlocking {
        try {
            block()
            fail("expected IOException")
        } catch (_: IOException) {
        }
    }

    @Test fun `the cover client never follows a redirect on its own`() {
        val c = coverSearchHttpClient(app, "UA")
        assertEquals(false, c.followRedirects)
        assertEquals(false, c.followSslRedirects)
    }

    @Test fun `open library's real two hop chain to the archive is followed`() = runBlocking {
        val c = web(olCover to archive, archive to iaNode, iaNode to "body:jpeg")
        assertEquals("jpeg", String(c.getCapped(olCover, 10)))
    }

    @Test fun `a relative redirect stays on its host`() = runBlocking {
        val c = web(olCover to "/b/id/1-M.jpg", "https://covers.openlibrary.org/b/id/1-M.jpg" to "body:ok")
        assertEquals("ok", String(c.getCapped(olCover, 10)))
    }

    @Test fun `a redirect down to plain http is refused`() =
        refused { web(olCover to "http://archive.org/x.jpg", "http://archive.org/x.jpg" to "body:x").getCapped(olCover, 10) }

    @Test fun `a redirect to another host is refused`() =
        refused { web(olCover to "https://evil.example/x.jpg", "https://evil.example/x.jpg" to "body:x").getCapped(olCover, 10) }

    @Test fun `a redirect into the home network is refused`() =
        refused { web(olCover to "https://192.168.1.1/x", "https://192.168.1.1/x" to "body:x").getCapped(olCover, 10) }

    @Test fun `a host that only ends like archive dot org is refused`() =
        refused { web(olCover to "https://notarchive.org/x", "https://notarchive.org/x" to "body:x").getCapped(olCover, 10) }

    @Test fun `more than three hops is refused`() {
        val hops = (0..4).map { "https://covers.openlibrary.org/h$it" }
        val routes = hops.zipWithNext().map { (a, b) -> a to b } + (hops.last() to "body:x")
        refused { web(*routes.toTypedArray()).getCapped(hops.first(), 10) }
        val three = (0..3).map { "https://covers.openlibrary.org/h$it" }
        val ok = three.zipWithNext().map { (a, b) -> a to b } + (three.last() to "body:x")
        runBlocking { assertEquals("x", String(web(*ok.toTypedArray()).getCapped(three.first(), 10))) }
    }

    @Test fun `a first request off the known hosts is refused`() =
        refused { web("https://evil.example/a" to "body:x").getCapped("https://evil.example/a", 10) }

    @Test fun `the known cover hosts`() {
        listOf("openlibrary.org", "covers.openlibrary.org", "archive.org", "ia600505.us.archive.org", "audiobookcovers.com", "images.audiobookcovers.com")
            .forEach { assertTrue(it, isCoverServiceHost(it)) }
        listOf("evil.example", "notarchive.org", "archive.org.evil.example", "192.168.1.1", "localhost", "openlibrary.org.evil.example", "evilaudiobookcovers.com")
            .forEach { assertEquals(it, false, isCoverServiceHost(it)) }
    }
}
