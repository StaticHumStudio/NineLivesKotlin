package com.ninelivesaudio.app.ui.library

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

class LibraryFilterRequestTest {

    @Test
    fun `a delayed old filter cannot overwrite the newer filter publication`() = runBlocking {
        val publication = LibraryFilterPublication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val aStarted = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<String>()
        var visibleShelf = "initial"

        // A is a slow query on the same library. B represents the user's next
        // query or sort change, so only B's later generation may publish.
        val aRequest = publication.replace("library")
        val aJob = publication.launch(scope, aRequest, {
            withContext(NonCancellable) {
                aStarted.complete(Unit)
                releaseA.await()
            }
        }) { visibleShelf = it }
        aStarted.await()

        val bRequest = publication.replace("library")
        val bJob = publication.launch(scope, bRequest, { "B shelf" }) { visibleShelf = it }
        bJob.join()
        assertEquals("B shelf", visibleShelf)

        // A's data source ignores cancellation and completes after B.
        releaseA.complete("A shelf")
        aJob.join()
        assertEquals("B shelf", visibleShelf)
        scope.cancel()
    }

    @Test
    fun `a new library request publishes normally when it remains current`() = runBlocking {
        val publication = LibraryFilterPublication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var visibleShelf = "initial"

        val request = publication.replace("library-b")
        publication.launch(scope, request, { "library B shelf" }) { visibleShelf = it }.join()

        assertEquals("library B shelf", visibleShelf)
        scope.cancel()
    }

    @Test
    fun `a failing filter query reports instead of escaping the scope`() = runBlocking {
        val publication = LibraryFilterPublication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var reported: String? = null
        var published = false

        val request = publication.replace("library")
        val job = publication.launch(
            scope,
            request,
            load = { throw IllegalStateException("room is closed") },
            onFailure = { reported = it.message },
        ) { _: String -> published = true }
        job.join()

        assertEquals("room is closed", reported)
        assertEquals(false, published)
        assertEquals(false, job.isCancelled)
        scope.cancel()
    }
}
