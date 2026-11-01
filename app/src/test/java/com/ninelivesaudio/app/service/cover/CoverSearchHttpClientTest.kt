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
        assertEquals("abc", String(clientThatAnswers(200, "abc").getCapped("https://x.example/a", 10)))
    }

    @Test fun `a capped get over the cap fails`() = runBlocking {
        try {
            clientThatAnswers(200, "x".repeat(20)).getCapped("https://x.example/a", 10)
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
}
