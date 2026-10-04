package com.ninelivesaudio.app.ui.player

import com.ninelivesaudio.app.data.remote.RemoteResult
import com.ninelivesaudio.app.domain.model.Bookmark
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkPublicationTest {

    private val aBookmarks = listOf(Bookmark(id = "a", libraryItemId = "book-a"))
    private val bBookmarks = listOf(Bookmark(id = "b", libraryItemId = "book-b"))

    @Test
    fun `delayed old success cannot replace the current book bookmarks`() = runBlocking {
        val publication = BookmarkPublication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val aStarted = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<RemoteResult<List<Bookmark>>>()
        var visible = emptyList<Bookmark>()

        val aRequest = publication.replace("book-a")
        val aJob = requireNotNull(publication.launch(scope, aRequest, { itemId ->
            assertEquals("book-a", itemId)
            withContext(NonCancellable) {
                aStarted.complete(Unit)
                releaseA.await()
            }
        }) { visible = bookmarkListState(visible, it).bookmarks })
        aStarted.await()

        val bRequest = publication.replace("book-b")
        val bJob = requireNotNull(publication.launch(scope, bRequest, { RemoteResult.Ok(bBookmarks) }) { visible = bookmarkListState(visible, it).bookmarks })
        bJob.join()
        assertEquals(bBookmarks, visible)

        // This simulates a repository that catches cancellation and still
        // returns its already-started A response.
        releaseA.complete(RemoteResult.Ok(aBookmarks))
        aJob.join()
        assertEquals(bBookmarks, visible)
        scope.cancel()
    }

    @Test
    fun `delayed old failure cannot clear the current book bookmarks`() = runBlocking {
        val publication = BookmarkPublication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val aStarted = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>()
        var visible = emptyList<Bookmark>()

        val aRequest = publication.replace("book-a")
        val aJob = requireNotNull(publication.launch(scope, aRequest, {
            withContext(NonCancellable) {
                aStarted.complete(Unit)
                releaseA.await()
            }
            error("A failed after B became current")
        }) { visible = bookmarkListState(visible, it).bookmarks })
        aStarted.await()

        val bRequest = publication.replace("book-b")
        val bJob = requireNotNull(publication.launch(scope, bRequest, { RemoteResult.Ok(bBookmarks) }) { visible = bookmarkListState(visible, it).bookmarks })
        bJob.join()
        assertEquals(bBookmarks, visible)

        releaseA.complete(Unit)
        aJob.join()
        assertEquals(bBookmarks, visible)
        scope.cancel()
    }

    @Test
    fun `a thrown load publishes a failure, not an empty list`() = runBlocking {
        val publication = BookmarkPublication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var published: RemoteResult<List<Bookmark>>? = null

        val request = publication.replace("book-a")
        requireNotNull(publication.launch(scope, request, { error("socket closed") }) { published = it }).join()

        assertTrue(published is RemoteResult.Failed)
        scope.cancel()
    }

    @Test
    fun `a failed refresh keeps the bookmarks on screen and flags the failure`() {
        val state = bookmarkListState(aBookmarks, RemoteResult.Failed("timeout"))
        assertEquals(aBookmarks, state.bookmarks)
        assertTrue(state.loadFailed)
    }

    @Test
    fun `a good load clears the failure flag`() {
        val state = bookmarkListState(aBookmarks, RemoteResult.Ok(bBookmarks))
        assertEquals(bBookmarks, state.bookmarks)
        assertFalse(state.loadFailed)
    }
}
