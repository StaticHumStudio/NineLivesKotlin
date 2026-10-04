package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.data.local.entity.AutoBrowseRow
import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.domain.model.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android Auto's Library and Downloaded lists used to load, decode and sort
 * the whole library, then keep 50. They now hand SQL a LIMIT and OFFSET, so
 * these pin what gets asked for.
 */
class AutoBrowsePageTest {

    private val server = AppSettings(appMode = AppMode.AUDIOBOOKSHELF, selectedLibraryId = "lib")

    private data class Ask(val libraryId: String, val isLocal: Boolean, val limit: Int, val offset: Int)

    private suspend fun ask(settings: AppSettings, page: Int, pageSize: Int): Ask? {
        var asked: Ask? = null
        autoBrowsePage(settings, page, pageSize) { libraryId, isLocal, limit, offset ->
            asked = Ask(libraryId, isLocal, limit, offset)
            emptyList<Unit>()
        }
        return asked
    }

    @Test
    fun `a page asks SQL for exactly that page`() = runBlocking {
        assertEquals(Ask("lib", false, 50, 0), ask(server, page = 0, pageSize = 50))
        assertEquals(Ask("lib", false, 50, 150), ask(server, page = 3, pageSize = 50))
    }

    @Test
    fun `local mode pages the local library`() = runBlocking {
        val local = AppSettings(
            appMode = AppMode.LOCAL,
            selectedLibraryId = "server-lib",
            selectedLocalLibraryId = "local-lib",
        )
        assertEquals(Ask("local-lib", true, 20, 20), ask(local, page = 1, pageSize = 20))
    }

    @Test
    fun `a browser that does not page still reads a bounded page`() = runBlocking {
        assertEquals(
            Ask("lib", false, AUTO_BROWSE_MAX_PAGE_SIZE, 0),
            ask(server, page = 0, pageSize = Int.MAX_VALUE),
        )
    }

    @Test
    fun `an oversized page never skips books past the cap`() = runBlocking {
        // Capping the limit but not the offset used to read books 1 to 500
        // then 1001 to 1500, so 501 to 1000 were never listed.
        assertEquals(Ask("lib", false, AUTO_BROWSE_MAX_PAGE_SIZE, 0), ask(server, page = 0, pageSize = 1000))
        assertNull(ask(server, page = 1, pageSize = 1000))
    }

    @Test
    fun `nonsense paging reads nothing`() = runBlocking {
        assertNull(ask(server, page = -1, pageSize = 50))
        assertNull(ask(server, page = 0, pageSize = 0))
        assertNull(ask(server, page = Int.MAX_VALUE, pageSize = Int.MAX_VALUE))
    }

    @Test
    fun `no active library reads nothing`() = runBlocking {
        assertNull(ask(AppSettings(appMode = AppMode.AUDIOBOOKSHELF), page = 0, pageSize = 50))
    }

    @Test
    fun `the page comes back as SQL returned it`() = runBlocking {
        val rows = autoBrowsePage(server, page = 0, pageSize = 2) { _, _, limit, _ ->
            listOf("b", "a", "c").take(limit)
        }
        assertEquals(listOf("b", "a"), rows)
    }

    @Test
    fun `a light row keeps the first genre and its covers`() {
        val item = AutoBrowseRow(
            id = "x",
            title = "Title",
            author = null,
            narrator = "Reader",
            coverPath = "https://abs.test/api/items/x/cover",
            localCoverPath = "file:///covers/x.jpg",
            genresJson = """["Mystery","Noir"]""",
        ).toAutoBookItem()

        assertEquals("Mystery", item.genre)
        assertEquals("", item.author)
        assertEquals("file:///covers/x.jpg", item.effectiveCoverPath)
    }

    @Test
    fun `broken or empty genres read as none`() {
        assertNull(AutoBrowseRow(id = "x", title = "T", genresJson = "not json").toAutoBookItem().genre)
        assertNull(AutoBrowseRow(id = "x", title = "T", genresJson = "[]").toAutoBookItem().genre)
        assertTrue(AutoBrowseRow(id = "x", title = "T").toAutoBookItem().genre == null)
    }
}
