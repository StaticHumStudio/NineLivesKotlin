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
}
