package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.domain.model.AppSettings
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.service.ConnectivityMonitor.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

class PlaybackRestorePolicyTest {

    @Test
    fun `restores local item paused at saved position`() {
        val settings = AppSettings(
            appMode = AppMode.LOCAL,
            selectedLocalLibraryId = "local-library",
        )
        val book = AudioBook(
            id = "local-book",
            libraryId = "local-library",
            isLocal = true,
            currentTime = 2.minutes,
        )

        val plan = resolvePlaybackRestore(
            persistedBookId = book.id,
            storedBook = book,
            settings = settings,
            savedPosition = 19.minutes,
        )

        assertEquals(book.id, plan?.book?.id)
        assertEquals(19.minutes, plan?.position)
        assertFalse(plan!!.playWhenReady)
    }

    @Test
    fun `restores server item in the selected server library`() {
        val settings = AppSettings(
            appMode = AppMode.AUDIOBOOKSHELF,
            selectedLibraryId = "server-library",
            selectedLocalLibraryId = "local-library",
        )
        val book = AudioBook(
            id = "server-book",
            libraryId = "server-library",
            isLocal = false,
        )

        val plan = resolvePlaybackRestore(book.id, book, settings, 7.minutes)

        assertEquals(book.id, plan?.book?.id)
        assertEquals(7.minutes, plan?.book?.currentTime)
        assertFalse(plan!!.playWhenReady)
    }

    @Test
    fun `missing stored item produces no restore plan`() {
        assertNull(
            resolvePlaybackRestore(
                persistedBookId = "missing",
                storedBook = null,
                settings = AppSettings(appMode = AppMode.LOCAL, selectedLocalLibraryId = "local-library"),
                savedPosition = 3.minutes,
            )
        )
    }

    @Test
    fun `restore never crosses the active library scope`() {
        val settings = AppSettings(
            appMode = AppMode.LOCAL,
            selectedLibraryId = "server-library",
            selectedLocalLibraryId = "local-library",
        )
        val serverBook = AudioBook(
            id = "server-book",
            libraryId = "server-library",
            isLocal = false,
        )

        assertNull(resolvePlaybackRestore(serverBook.id, serverBook, settings, 5.minutes))
    }

    @Test
    fun `newer book position is never moved backward by stale saved progress`() {
        val settings = AppSettings(
            appMode = AppMode.LOCAL,
            selectedLocalLibraryId = "local-library",
        )
        val book = AudioBook(
            id = "local-book",
            libraryId = "local-library",
            isLocal = true,
            currentTime = 12.minutes,
        )

        val plan = resolvePlaybackRestore(book.id, book, settings, 5.minutes)

        assertEquals(12.minutes, plan?.position)
        assertTrue(plan?.book?.currentTime == 12.minutes)
    }

    @Test
    fun `server restore probes reachability when startup status is still offline`() {
        val book = AudioBook(id = "server-book", isLocal = false)

        assertTrue(
            shouldProbeServerBeforeRestore(
                book = book,
                connectionStatus = ConnectionStatus.OFFLINE,
                localDownloadAvailable = false,
            )
        )
    }

    @Test
    fun `local and already connected restores do not probe reachability`() {
        assertFalse(
            shouldProbeServerBeforeRestore(
                book = AudioBook(id = "local-book", isLocal = true),
                connectionStatus = ConnectionStatus.OFFLINE,
            )
        )
        assertFalse(
            shouldProbeServerBeforeRestore(
                book = AudioBook(id = "server-book", isLocal = false),
                connectionStatus = ConnectionStatus.CONNECTED,
                localDownloadAvailable = false,
            )
        )
    }
}
