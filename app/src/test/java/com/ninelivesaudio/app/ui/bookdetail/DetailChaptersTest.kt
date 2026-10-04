package com.ninelivesaudio.app.ui.bookdetail

import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.Chapter
import com.ninelivesaudio.app.service.ConnectivityMonitor.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The library list sync carries no chapters, so the detail screen hid its
 * Chapters section for nearly every server book. It now asks the server for
 * the single item when the row has none, and shows them without saving them.
 */
class DetailChaptersTest {

    private val listSynced = AudioBook(id = "b1", title = "Book")
    private val one = Chapter(id = 0, start = 0.0, end = 60.0, title = "One")
    private val two = Chapter(id = 1, start = 60.0, end = 120.0, title = "Two")

    @Test
    fun `a list-synced server book asks for its chapters while connected`() {
        assertTrue(shouldFetchDetailChapters(listSynced, AppMode.AUDIOBOOKSHELF, ConnectionStatus.CONNECTED))
        assertTrue(shouldFetchDetailChapters(listSynced, AppMode.AUDIOBOOKSHELF, ConnectionStatus.SYNCING))
    }

    @Test
    fun `no fetch while offline or the server is unreachable`() {
        assertFalse(shouldFetchDetailChapters(listSynced, AppMode.AUDIOBOOKSHELF, ConnectionStatus.OFFLINE))
        assertFalse(shouldFetchDetailChapters(listSynced, AppMode.AUDIOBOOKSHELF, ConnectionStatus.SERVER_UNREACHABLE))
    }

    @Test
    fun `a row that already has chapters does not refetch`() {
        val withChapters = listSynced.copy(chapters = listOf(one))
        assertFalse(shouldFetchDetailChapters(withChapters, AppMode.AUDIOBOOKSHELF, ConnectionStatus.CONNECTED))
    }

    @Test
    fun `scanned-local and archived books never ask the server`() {
        assertFalse(shouldFetchDetailChapters(listSynced.copy(isLocal = true), AppMode.LOCAL, ConnectionStatus.CONNECTED))
        assertFalse(shouldFetchDetailChapters(listSynced, AppMode.LOCAL, ConnectionStatus.CONNECTED))
        assertFalse(shouldFetchDetailChapters(listSynced.copy(archivedAt = 1L), AppMode.AUDIOBOOKSHELF, ConnectionStatus.CONNECTED))
    }

    @Test
    fun `fetched chapters fill an empty row, in order`() {
        assertEquals(listOf(one, two), detailChapters(emptyList(), listOf(two, one)))
    }

    @Test
    fun `stored chapters win over fetched ones`() {
        assertEquals(listOf(one), detailChapters(listOf(one), listOf(one, two)))
    }

    @Test
    fun `nothing stored and nothing fetched shows no chapters`() {
        assertEquals(emptyList<Chapter>(), detailChapters(emptyList(), null))
    }
}
