package com.ninelivesaudio.app.ui.dossier

import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.domain.model.AppSettings
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.ListeningSession
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import kotlin.time.Duration

/**
 * The Dossier used to load and decode every book in the library on the main
 * thread to build stats for the few it had sessions for. At 50k books that
 * froze each chip tap for seconds. It now asks for the listened books only.
 */
class DossierBookLoadTest {

    private val serverSettings = AppSettings(
        appMode = AppMode.AUDIOBOOKSHELF,
        selectedLibraryId = "lib",
    )

    @Test
    fun `asks only for the books the sessions listened to, once each`() = runBlocking {
        val requests = mutableListOf<Triple<String, Boolean, List<String>>>()
        val sessions = listOf(session("a"), session("b"), session("a"), session("c"))

        val books = loadDossierBooks(sessions, serverSettings, fetchByIds = { libraryId, isLocal, ids ->
            requests += Triple(libraryId, isLocal, ids)
            ids.map { book(it) }
        })

        assertEquals(listOf(Triple("lib", false, listOf("a", "b", "c"))), requests)
        assertEquals(setOf("a", "b", "c"), books.keys)
    }

    @Test
    fun `no sessions means no lookup at all`() = runBlocking {
        var calls = 0
        val books = loadDossierBooks(emptyList(), serverSettings, fetchByIds = { _, _, _ ->
            calls++
            emptyList()
        })

        assertEquals(0, calls)
        assertTrue(books.isEmpty())
    }

    @Test
    fun `no active library means no lookup and no books`() = runBlocking {
        var calls = 0
        val books = loadDossierBooks(
            listOf(session("a")),
            AppSettings(appMode = AppMode.AUDIOBOOKSHELF),
            fetchByIds = { _, _, ids ->
                calls++
                ids.map { book(it) }
            },
        )

        assertEquals(0, calls)
        assertTrue(books.isEmpty())
    }

    @Test
    fun `local mode asks the local library for local books`() = runBlocking {
        val settings = AppSettings(
            appMode = AppMode.LOCAL,
            selectedLibraryId = "server-lib",
            selectedLocalLibraryId = "local-lib",
        )
        var asked: Pair<String, Boolean>? = null

        loadDossierBooks(listOf(session("a")), settings, fetchByIds = { libraryId, isLocal, ids ->
            asked = libraryId to isLocal
            ids.map { book(it, libraryId = libraryId, isLocal = true) }
        })

        assertEquals("local-lib" to true, asked)
    }

    @Test
    fun `a book from another library or source is still dropped`() = runBlocking {
        val books = loadDossierBooks(
            listOf(session("good"), session("wrong-lib"), session("wrong-source")),
            serverSettings,
            fetchByIds = { _, _, _ ->
                listOf(
                    book("good"),
                    book("wrong-lib", libraryId = "other"),
                    book("wrong-source", isLocal = true),
                )
            },
        )

        assertEquals(setOf("good"), books.keys)
    }

    @Test
    fun `the lookup and decoding run off the calling thread`() {
        var mainThread: Thread? = null
        val fakeMain = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "fake-main").also { mainThread = it }
        }
        try {
            val lookupThread = runBlocking {
                withContext(fakeMain.asCoroutineDispatcher()) {
                    var seen: Thread? = null
                    loadDossierBooks(listOf(session("a")), serverSettings, fetchByIds = { _, _, ids ->
                        seen = Thread.currentThread()
                        ids.map { book(it) }
                    })
                    seen
                }
            }

            assertTrue(mainThread != null && lookupThread != null)
            assertNotSame(mainThread, lookupThread)
        } finally {
            fakeMain.shutdownNow()
        }
    }

    private fun book(id: String, libraryId: String = "lib", isLocal: Boolean = false) =
        AudioBook(id = id, libraryId = libraryId, isLocal = isLocal)

    private fun session(bookId: String) = ListeningSession(
        id = "s-$bookId-${System.nanoTime()}",
        libraryItemId = bookId,
        currentTime = Duration.ZERO,
        timeListening = Duration.ZERO,
        startedAt = 0L,
        updatedAt = 0L,
        displayTitle = null,
    )
}
