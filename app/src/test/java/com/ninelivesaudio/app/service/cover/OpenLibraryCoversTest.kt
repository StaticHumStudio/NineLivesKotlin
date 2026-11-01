package com.ninelivesaudio.app.service.cover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenLibraryCoversTest {

    @Test fun `the search asks only for the fields it needs`() {
        val url = openLibrarySearchUrl("dracula bram stoker")
        assertEquals("https", url.scheme)
        assertEquals("openlibrary.org", url.host)
        assertEquals("/search.json", url.encodedPath)
        assertEquals("dracula bram stoker", url.queryParameter("q"))
        assertEquals("key,title,author_name,cover_i", url.queryParameter("fields"))
        assertEquals("30", url.queryParameter("limit"))
    }

    @Test fun `cover urls use the cover id and ask for a 404 instead of a blank`() =
        assertEquals("https://covers.openlibrary.org/b/id/12216503-M.jpg?default=false", openLibraryCoverUrl(12216503, 'M'))

    @Test fun `docs with a cover id become candidates with medium and large urls`() {
        val c = parseOpenLibrarySearch("""{"numFound":1,"start":0,"docs":[{"key":"/works/OL85892W","title":"Dracula","author_name":["Bram Stoker"],"cover_i":12216503}]}""")!!.single()
        assertEquals("ol:12216503", c.key)
        assertEquals(openLibraryCoverUrl(12216503, 'M'), c.thumbnailUrl)
        assertEquals(openLibraryCoverUrl(12216503, 'L'), c.fullUrl)
        assertEquals("Dracula", c.title)
        assertEquals("Bram Stoker", c.author)
    }

    @Test fun `docs without a cover id are skipped`() =
        assertEquals(emptyList<CoverCandidate>(), parseOpenLibrarySearch("""{"numFound":1,"docs":[{"key":"/works/x","title":"Dracula"}]}"""))

    @Test fun `two editions sharing a cover id show once`() =
        assertEquals(1, parseOpenLibrarySearch("""{"numFound":2,"docs":[{"cover_i":5},{"cover_i":5}]}""")!!.size)

    @Test fun `a negative cover id is skipped`() =
        assertEquals(0, parseOpenLibrarySearch("""{"numFound":1,"docs":[{"cover_i":-1}]}""")!!.size)

    @Test fun `no matches is an empty list`() =
        assertEquals(emptyList<CoverCandidate>(), parseOpenLibrarySearch("""{"numFound":0,"start":0,"docs":[]}"""))

    @Test fun `a body that is not search json is a failure, not an empty result`() {
        assertNull(parseOpenLibrarySearch("<html>busy</html>"))
        assertNull(parseOpenLibrarySearch("""{"error":"x"}"""))
    }
}
