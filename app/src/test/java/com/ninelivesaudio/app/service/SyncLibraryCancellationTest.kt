package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.data.remote.RemoteResult
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.Library
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class SyncLibraryCancellationTest {

    @Test
    fun `library list cancellation is rethrown`() = runBlocking {
        val cancellation = CancellationException("stop library sync")

        try {
            fetchLibrarySyncReport(
                fetchLibraries = { throw cancellation },
                fetchItems = { RemoteResult.Ok(emptyList<AudioBook>()) },
            )
            fail("Expected library sync cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    @Test
    fun `library item cancellation is rethrown`() = runBlocking {
        val cancellation = CancellationException("stop item sync")

        try {
            fetchLibrarySyncReport(
                fetchLibraries = { RemoteResult.Ok(listOf(Library(id = "books", name = "Books"))) },
                fetchItems = { throw cancellation },
            )
            fail("Expected item sync cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }
}
