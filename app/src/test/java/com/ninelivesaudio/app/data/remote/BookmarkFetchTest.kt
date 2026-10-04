package com.ninelivesaudio.app.data.remote

import com.ninelivesaudio.app.domain.model.Bookmark
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Opening a book used to download all of /api/me (every progress row the
 * account owns) just to read that book's bookmarks, and any failure showed up
 * as an empty list. These pin the narrow route first, a short-lived shared
 * /api/me fallback for older servers, and failures that stay failures.
 */
class BookmarkFetchTest {

    private val a1 = Bookmark(id = "1", libraryItemId = "book-a", time = 90.0)
    private val a2 = Bookmark(id = "2", libraryItemId = "book-a", time = 30.0)
    private val b1 = Bookmark(id = "3", libraryItemId = "book-b", time = 10.0)

    private var clock = 0L
    private val cache = MeBookmarksCache(ttlMs = 1_000L, now = { clock })
    private var meCalls = 0

    private fun allFromMe(): RemoteResult<List<Bookmark>> {
        meCalls++
        return RemoteResult.Ok(listOf(a1, a2, b1))
    }

    private suspend fun load(
        itemId: String,
        session: String = "s1",
        item: ItemBookmarksOutcome = ItemBookmarksOutcome.Unsupported,
        all: suspend () -> RemoteResult<List<Bookmark>> = ::allFromMe,
    ) = loadItemBookmarks(itemId, session, cache, { item }, all)

    @Test
    fun `a server with the per-book route never touches api me`() = runBlocking {
        val result = load("book-a", item = ItemBookmarksOutcome.Found(listOf(a1, a2)))
        assertEquals(RemoteResult.Ok(listOf(a2, a1)), result)
        assertEquals(0, meCalls)
    }

    @Test
    fun `an older server falls back to api me once and shares it across books`() = runBlocking {
        assertEquals(RemoteResult.Ok(listOf(a2, a1)), load("book-a"))
        assertEquals(RemoteResult.Ok(listOf(b1)), load("book-b"))
        assertEquals(1, meCalls)
    }

    @Test
    fun `the cached profile refreshes after the window`() = runBlocking {
        load("book-a")
        clock = 1_000L
        load("book-a")
        assertEquals(2, meCalls)
    }

    @Test
    fun `a new server or account does not reuse the last one's bookmarks`() = runBlocking {
        load("book-a", session = "s1")
        load("book-a", session = "s2")
        assertEquals(2, meCalls)
    }

    @Test
    fun `adding or removing a bookmark drops the cached list`() = runBlocking {
        load("book-a")
        cache.invalidate()
        load("book-a")
        assertEquals(2, meCalls)
    }

    @Test
    fun `a change during the fetch is not cached as current`() = runBlocking {
        load("book-a", all = {
            cache.invalidate()
            allFromMe()
        })
        load("book-a")
        assertEquals(2, meCalls)
    }

    @Test
    fun `a failed per-book call is a failure, not an empty list`() = runBlocking {
        val result = load("book-a", item = ItemBookmarksOutcome.Failed("HTTP 500 loading bookmarks"))
        assertEquals(RemoteResult.Failed("HTTP 500 loading bookmarks"), result)
        assertEquals(0, meCalls)
    }

    @Test
    fun `a thrown api me fallback is a failure and is not cached`() = runBlocking {
        val result = load("book-a", all = { throw IOException("timeout") })
        assertTrue(result is RemoteResult.Failed)
        assertEquals(RemoteResult.Ok(listOf(a2, a1)), load("book-a"))
        assertEquals(1, meCalls)
    }
}
