package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.domain.model.AudioBook
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Deleting a download while the player holds it must not leave the player
 * reaching for deleted files (#89). Only a book loaded from disk is unloaded.
 */
class DownloadRemovalTest {

    private val fromDisk = AudioBook(id = "b", isDownloaded = true, localPath = "/d/b")

    @Test
    fun `a deleted download the player loaded from disk unloads it`() {
        assertEquals(DownloadRemovalAction.UNLOAD, downloadRemovalAction(fromDisk, "b", loading = false))
    }

    @Test
    fun `a deleted download of the book still loading stops the load`() {
        assertEquals(DownloadRemovalAction.STOP_LOAD_AND_UNLOAD, downloadRemovalAction(fromDisk, "b", loading = true))
    }

    @Test
    fun `a deleted download of another book leaves the player alone`() {
        assertEquals(DownloadRemovalAction.NONE, downloadRemovalAction(fromDisk, "other", loading = false))
    }

    @Test
    fun `a book the player streams keeps playing when its download goes`() {
        val streaming = fromDisk.copy(isDownloaded = false, localPath = null)

        assertEquals(DownloadRemovalAction.NONE, downloadRemovalAction(streaming, "b", loading = false))
        assertEquals(DownloadRemovalAction.NONE, downloadRemovalAction(streaming, "b", loading = true))
    }

    @Test
    fun `a local import is never unloaded by a server download delete`() {
        assertEquals(DownloadRemovalAction.NONE, downloadRemovalAction(fromDisk.copy(isLocal = true), "b", loading = false))
    }

    @Test
    fun `nothing loaded means nothing to unload`() {
        assertEquals(DownloadRemovalAction.NONE, downloadRemovalAction(null, "b", loading = false))
    }

    @Test
    fun `deleting a book's download cancels a load of it that has not claimed yet`() = kotlinx.coroutines.runBlocking {
        val owner = PlaybackLoadOwner()
        val request = owner.newRequest()
        owner.trackLoad(request, "b")

        assertEquals(true, owner.cancelLoadsOf("b"))

        assertEquals(false, owner.claim(request))
    }

    @Test
    fun `deleting a book's download cancels its claimed load`() = kotlinx.coroutines.runBlocking {
        val owner = PlaybackLoadOwner()
        val request = owner.newRequest()
        owner.trackLoad(request, "b")
        assertEquals(true, owner.claim(request))

        assertEquals(true, owner.cancelLoadsOf("b"))

        assertEquals(false, owner.isCurrent(request))
    }

    @Test
    fun `deleting one book's download leaves another book's load alone`() = kotlinx.coroutines.runBlocking {
        val owner = PlaybackLoadOwner()
        val other = owner.newRequest()
        owner.trackLoad(other, "c")

        assertEquals(false, owner.cancelLoadsOf("b"))

        assertEquals(true, owner.claim(other))
    }

    @Test
    fun `a finished load is no longer tracked`() {
        val owner = PlaybackLoadOwner()
        val request = owner.newRequest()
        owner.trackLoad(request, "b")
        owner.abandon(request)

        assertEquals(false, owner.cancelLoadsOf("b"))
    }

    @Test
    fun `deleting the download of the book that is loading stops the load before it is current`() {
        assertEquals(
            DownloadRemovalAction.STOP_LOAD_AND_UNLOAD,
            downloadRemovalTarget(current = AudioBook(id = "a"), loading = fromDisk, removedBookId = "b"),
        )
    }

    @Test
    fun `deleting the retained book's download while another book loads only refreshes its row`() {
        assertEquals(
            DownloadRemovalAction.REFRESH_ONLY,
            downloadRemovalTarget(current = fromDisk, loading = AudioBook(id = "c"), removedBookId = "b"),
        )
    }

    @Test
    fun `with no load running the current book follows the existing rule`() {
        assertEquals(DownloadRemovalAction.UNLOAD, downloadRemovalTarget(fromDisk, null, "b"))
        assertEquals(
            DownloadRemovalAction.NONE,
            downloadRemovalTarget(fromDisk.copy(isDownloaded = false, localPath = null), null, "b"),
        )
    }

    @Test
    fun `deleting a streamed loading book's download leaves the load alone`() {
        assertEquals(
            DownloadRemovalAction.NONE,
            downloadRemovalTarget(fromDisk.copy(id = "a"), fromDisk.copy(isDownloaded = false, localPath = null), "b"),
        )
    }
}
