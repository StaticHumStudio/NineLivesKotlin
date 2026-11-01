package com.ninelivesaudio.app.service.cover

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** Bodies are trimmed copies of a live /api/search answer (2026-10-31). */
class AudiobookCoversTest {

    private fun result(id: String) =
        """{"id":"$id","url":"https://images.audiobookcovers.com/original/$id.jpg","source":"https://redd.it/x","score":0.21,""" +
            """"images":{"jpeg":{"320":"https://images.audiobookcovers.com/jpeg/320/$id.jpg","640":"https://images.audiobookcovers.com/jpeg/640/$id.jpg","1280":"https://images.audiobookcovers.com/jpeg/1280/$id.jpg"}}}"""

    @Test fun `the search sends only the text`() {
        val url = audiobookCoversSearchUrl("carmilla le fanu")
        assertEquals("https", url.scheme)
        assertEquals("audiobookcovers.com", url.host)
        assertEquals("/api/search", url.encodedPath)
        assertEquals("carmilla le fanu", url.queryParameter("q"))
        assertEquals(setOf("q"), url.queryParameterNames)
    }

    @Test fun `results become candidates with the 320 thumbnail and the 1280 image`() {
        val c = parseAudiobookCoversSearch("""{"results":[${result("abc")}],"count":1,"query":"carmilla","model":"jina-clip-v2"}""")!!.single()
        assertEquals(CoverSourceId.AUDIOBOOK_COVERS, c.source)
        assertEquals("abc:abc", c.key)
        assertEquals("https://images.audiobookcovers.com/jpeg/320/abc.jpg", c.thumbnailUrl)
        assertEquals("https://images.audiobookcovers.com/jpeg/1280/abc.jpg", c.fullUrl)
    }

    @Test fun `a result without sized images falls back to the original`() {
        val c = parseAudiobookCoversSearch("""{"results":[{"id":"x","url":"https://images.audiobookcovers.com/original/x.png"}]}""")!!.single()
        assertEquals("https://images.audiobookcovers.com/original/x.png", c.thumbnailUrl)
        assertEquals("https://images.audiobookcovers.com/original/x.png", c.fullUrl)
    }

    @Test fun `a result with no image or no id is skipped`() =
        assertEquals(0, parseAudiobookCoversSearch("""{"results":[{"id":"x"},{"url":"https://images.audiobookcovers.com/original/y.jpg"}]}""")!!.size)

    @Test fun `only https images are used`() =
        assertEquals(0, parseAudiobookCoversSearch("""{"results":[{"id":"x","url":"http://images.audiobookcovers.com/original/x.jpg"}]}""")!!.size)

    @Test fun `its similarity search always answers, so only the closest few are kept`() {
        val body = """{"results":[${(1..20).joinToString(",") { result("r$it") }}]}"""
        assertEquals((1..AUDIOBOOK_COVERS_MAX_RESULTS).map { "abc:r$it" }, parseAudiobookCoversSearch(body)!!.map { it.key })
    }

    @Test fun `no results is an empty list`() =
        assertEquals(emptyList<CoverCandidate>(), parseAudiobookCoversSearch("""{"results":[],"count":0}"""))

    @Test fun `a body that is not search json is a failure`() {
        assertNull(parseAudiobookCoversSearch("<html>busy</html>"))
        assertNull(parseAudiobookCoversSearch("""{"error":"x"}"""))
    }

    @Test fun `a search that answers 503 fails the source`() = runBlocking {
        val client = coverSearchHttpClient(OkHttpClient(), "UA").newBuilder()
            .addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(503).message("x")
                    .body("busy".toResponseBody()).build()
            }.build()
        try {
            AudiobookCoversSource.onCoverClient(client).search("x")
            fail("expected IOException")
        } catch (_: IOException) {
        }
    }
}
