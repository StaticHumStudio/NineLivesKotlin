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
        assertEquals("abc:3b7548e0-004e-4918-b845-825f5683305b", first.key)
        assertEquals("https://images.audiobookcovers.com/jpeg/320/3b7548e0-004e-4918-b845-825f5683305b.jpg", first.thumbnailUrl)
        assertEquals("https://images.audiobookcovers.com/jpeg/1280/3b7548e0-004e-4918-b845-825f5683305b.jpg", first.fullUrl)
    }

    /**
     * The site ties each cover to an Open Library book with a confidence. On this page an
     * omnibus (The Martian / Artemis / Project Hail Mary) pulls in Martian covers as LIKELY
     * and UNCERTAIN, while the one CONFIRMED cover is the real Project Hail Mary art.
     */
    @Test fun `surer matches come first, then the page's own order`() {
        val keys = (parseAudiobookCoversPage(hailMary) as AudiobookCoversPage.Covers).candidates.map { it.key.removePrefix("abc:").take(8) }
        assertEquals(
            listOf("3b7548e0", "0aec0ced", "692d7e8f", "a4fcbb00", "cecf4fb3", "d127ccd1", "fd488e6d", "a12e9a15", "ef363b50"),
            keys,
        )
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
