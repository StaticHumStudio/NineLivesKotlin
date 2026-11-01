package com.ninelivesaudio.app.service.cover

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * The fixtures are the real audiobookcovers.com book search pages (2026-10-31), saved
 * whole: Project Hail Mary by Andy Weir has 9 matched covers, Carmilla has none.
 */
class AudiobookCoversTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader!!.getResource("cover/$name")) { "missing fixture $name" }.readText()

    private val hailMary get() = fixture("abc-book-search-project-hail-mary.html")
    private val carmilla get() = fixture("abc-book-search-carmilla.html")

    private fun clientThatAnswers(code: Int, body: String) = coverSearchHttpClient(OkHttpClient(), "UA").newBuilder()
        .addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body(body.toResponseBody()).build()
        }.build()

    @Test fun `the search asks the book search page for the title and author`() {
        val url = audiobookCoversSearchUrl(CoverQuery("Project Hail Mary Andy Weir", "Project Hail Mary", "Andy Weir"))
        assertEquals("https", url.scheme)
        assertEquals("audiobookcovers.com", url.host)
        assertEquals("/search", url.encodedPath)
        assertEquals("Project Hail Mary", url.queryParameter("title"))
        assertEquals("Andy Weir", url.queryParameter("author"))
        assertEquals(setOf("title", "author"), url.queryParameterNames)
    }

    @Test fun `no author sends only the title`() {
        val url = audiobookCoversSearchUrl(CoverQuery("carmilla le fanu", "carmilla le fanu", null))
        assertEquals("carmilla le fanu", url.queryParameter("title"))
        assertEquals(setOf("title"), url.queryParameterNames)
    }

    @Test fun `the hail mary page has its 9 matched covers`() {
        val found = parseAudiobookCoversPage(hailMary)
        assertTrue(found is AudiobookCoversPage.Covers)
        val covers = (found as AudiobookCoversPage.Covers).candidates
        assertEquals(9, covers.size)
        assertEquals(9, covers.map { it.fullUrl }.toSet().size)
        covers.forEach {
            assertEquals(CoverSourceId.AUDIOBOOK_COVERS, it.source)
            assertTrue(it.key, it.key.startsWith("abc:"))
            assertTrue(it.thumbnailUrl, it.thumbnailUrl.startsWith("https://images.audiobookcovers.com/jpeg/320/"))
            assertTrue(it.fullUrl, it.fullUrl.startsWith("https://images.audiobookcovers.com/jpeg/1280/"))
        }
        val first = covers.first()
        assertEquals("abc:0aec0ced-1758-446a-8c57-3364f46ef8b6", first.key)
        assertEquals("https://images.audiobookcovers.com/jpeg/320/0aec0ced-1758-446a-8c57-3364f46ef8b6.jpg", first.thumbnailUrl)
        assertEquals("https://images.audiobookcovers.com/jpeg/1280/0aec0ced-1758-446a-8c57-3364f46ef8b6.jpg", first.fullUrl)
    }

    @Test fun `the carmilla page is a real answer with no covers`() =
        assertEquals(AudiobookCoversPage.Covers(emptyList()), parseAudiobookCoversPage(carmilla))

    @Test fun `a page it does not recognise is unparseable, not empty`() {
        assertEquals(AudiobookCoversPage.Unparseable, parseAudiobookCoversPage("<html><body>We moved things around</body></html>"))
        assertEquals(AudiobookCoversPage.Unparseable, parseAudiobookCoversPage(""))
        assertEquals(AudiobookCoversPage.Unparseable, parseAudiobookCoversPage("""{"results":[]}"""))
    }

    @Test fun `a results list whose entries changed shape is unparseable`() {
        val reshaped = hailMary.replace("{id:\"", "{imageId:\"")
        assertEquals(AudiobookCoversPage.Unparseable, parseAudiobookCoversPage(reshaped))
    }

    @Test fun `only https images on the image host are used`() {
        val elsewhere = hailMary.replace("https://images.audiobookcovers.com/", "http://images.audiobookcovers.com/")
        assertEquals(AudiobookCoversPage.Unparseable, parseAudiobookCoversPage(elsewhere))
    }

    @Test fun `the source keeps the matched covers`() = runBlocking {
        val found = AudiobookCoversSource.onCoverClient(clientThatAnswers(200, hailMary))
            .search(CoverQuery("Project Hail Mary Andy Weir", "Project Hail Mary", "Andy Weir"))
        assertEquals(9, found.size)
    }

    @Test fun `the source answers nothing for carmilla`() = runBlocking {
        assertEquals(emptyList<CoverCandidate>(), AudiobookCoversSource.onCoverClient(clientThatAnswers(200, carmilla))
            .search(CoverQuery("Carmilla", "Carmilla", null)))
    }

    @Test fun `a changed page makes the source unparseable`() = runBlocking {
        try {
            AudiobookCoversSource.onCoverClient(clientThatAnswers(200, "<html>new look</html>")).search(CoverQuery("x", "x", null))
            fail("expected UnparseableCoverPageException")
        } catch (_: UnparseableCoverPageException) {
        }
    }

    @Test fun `a search that answers 503 fails the source`() = runBlocking {
        try {
            AudiobookCoversSource.onCoverClient(clientThatAnswers(503, "busy")).search(CoverQuery("x", "x", null))
            fail("expected IOException")
        } catch (e: IOException) {
            assertNull(e as? UnparseableCoverPageException)
        }
    }
}
