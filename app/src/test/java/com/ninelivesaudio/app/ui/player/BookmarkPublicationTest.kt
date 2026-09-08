package com.ninelivesaudio.app.ui.player

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
import org.junit.Test

class BookmarkPublicationTest {

    private val aBookmarks = listOf(Bookmark(id = "a", libraryItemId = "book-a"))
    private val bBookmarks = listOf(Bookmark(id = "b", libraryItemId = "book-b"))

    @Test
    fun `delayed old success cannot replace the current book bookmarks`() = runBlocking {
        val publication = BookmarkPublication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val aStarted = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<List<Bookmark>>()
        var visible = emptyList<Bookmark>()

        val aRequest = publication.replace("book-a")
        val aJob = requireNotNull(publication.launch(scope, aRequest, { itemId ->
            assertEquals("book-a", itemId)
            withContext(NonCancellable) {
                aStarted.complete(Unit)
                releaseA.await()
            }
        }) { visible = it })
        aStarted.await()

        val bRequest = publication.replace("book-b")
        val bJob = requireNotNull(publication.launch(scope, bRequest, { bBookmarks }) { visible = it })
        bJob.join()
        assertEquals(bBookmarks, visible)

        // This simulates a repository that catches cancellation and still
        // returns its already-started A response.
        releaseA.complete(aBookmarks)
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
        }) { visible = it })
        aStarted.await()

        val bRequest = publication.replace("book-b")
        val bJob = requireNotNull(publication.launch(scope, bRequest, { bBookmarks }) { visible = it })
        bJob.join()
        assertEquals(bBookmarks, visible)

        releaseA.complete(Unit)
        aJob.join()
        assertEquals(bBookmarks, visible)
        scope.cancel()
    }
}
