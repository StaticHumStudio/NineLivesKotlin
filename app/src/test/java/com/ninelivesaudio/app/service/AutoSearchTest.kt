package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.domain.model.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Auto search used to scan every library, return the whole catalog for an
 * empty query, and build a row and fetch a full-size cover for every match on
 * every page request. It now asks SQL for one capped page in one library.
 */
class AutoSearchTest {

    private val server = AppSettings(appMode = AppMode.AUDIOBOOKSHELF, selectedLibraryId = "lib")

    private data class Ask(val libraryId: String, val isLocal: Boolean, val pattern: String, val limit: Int, val offset: Int)

    private suspend fun ask(settings: AppSettings, query: String, page: Int, pageSize: Int): Ask? {
        var asked: Ask? = null
        autoSearchPage(settings, query, page, pageSize) { libraryId, isLocal, pattern, limit, offset ->
            asked = Ask(libraryId, isLocal, pattern, limit, offset)
            emptyList<Unit>()
        }
        return asked
    }

    @Test
    fun `the query becomes a contains pattern that matches wildcards literally`() {
        assertEquals("%dune%", autoSearchPattern("  dune "))
        assertEquals("%100\\%%", autoSearchPattern("100%"))
        assertEquals("%a\\_b%", autoSearchPattern("a_b"))
        assertEquals("%c:\\\\x%", autoSearchPattern("c:\\x"))
    }

    @Test
    fun `a blank query searches nothing`() = runBlocking {
        assertNull(autoSearchPattern(""))
        assertNull(autoSearchPattern("   "))
        assertNull(ask(server, "", page = 0, pageSize = 50))
        assertEquals(0, autoSearchCount(server, " ") { _, _, _, _ -> error("must not count") })
    }

    @Test
    fun `search stays in the active library and source`() = runBlocking {
        val local = AppSettings(
            appMode = AppMode.LOCAL,
            selectedLibraryId = "server-lib",
            selectedLocalLibraryId = "local-lib",
        )
        assertEquals(Ask("lib", false, "%x%", 20, 0), ask(server, "x", page = 0, pageSize = 20))
        assertEquals(Ask("local-lib", true, "%x%", 20, 0), ask(local, "x", page = 0, pageSize = 20))
        assertNull(ask(AppSettings(appMode = AppMode.AUDIOBOOKSHELF), "x", page = 0, pageSize = 20))
    }

    @Test
    fun `pages stop at the result cap`() = runBlocking {
        assertEquals(Ask("lib", false, "%a%", 50, 50), ask(server, "a", page = 1, pageSize = 50))
        assertEquals(Ask("lib", false, "%a%", 40, 60), ask(server, "a", page = 1, pageSize = 60))
        assertNull(ask(server, "a", page = 2, pageSize = 50))
        assertEquals(
            Ask("lib", false, "%a%", AUTO_SEARCH_RESULT_CAP, 0),
            ask(server, "a", page = 0, pageSize = Int.MAX_VALUE),
        )
        assertNull(ask(server, "a", page = -1, pageSize = 50))
        assertNull(ask(server, "a", page = 0, pageSize = 0))
    }

    @Test
    fun `the count never promises more than the cap`() = runBlocking {
        var askedCap = -1
        val count = autoSearchCount(server, "a") { libraryId, isLocal, pattern, cap ->
            assertEquals("lib", libraryId)
            assertEquals(false, isLocal)
            assertEquals("%a%", pattern)
            askedCap = cap
            5_000
        }
        assertEquals(AUTO_SEARCH_RESULT_CAP, askedCap)
        assertEquals(AUTO_SEARCH_RESULT_CAP, count)
        assertEquals(7, autoSearchCount(server, "a") { _, _, _, _ -> 7 })
    }

    @Test
    fun `Auto asks the server for browse-size covers`() {
        assertEquals(
            "https://abs.test/api/items/x/cover?width=256",
            autoArtworkRemoteUrl("https://abs.test/api/items/x/cover", 256),
        )
        assertEquals(
            "https://abs.test/api/items/x/cover?ts=1&width=256",
            autoArtworkRemoteUrl("https://abs.test/api/items/x/cover?ts=1", 256),
        )
        assertNull(autoArtworkRemoteUrl("file:///covers/x.jpg", 256))
        assertNull(autoArtworkRemoteUrl(null, 256))
    }
}
