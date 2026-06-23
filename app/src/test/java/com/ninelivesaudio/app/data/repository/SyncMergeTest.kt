package com.ninelivesaudio.app.data.repository

import com.ninelivesaudio.app.data.local.entity.AudioBookEntity
import com.ninelivesaudio.app.domain.model.AudioBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * A library sync must not wipe local download state. The cover persisted at
 * download time (localCoverPath) has to survive the merge, otherwise the next
 * sync (~5 min) drops the cover reference and downloaded books lose their
 * offline cover even though the file is still on disk.
 */
class SyncMergeTest {

    private fun localEntity(
        isDownloaded: Int = 1,
        localPath: String? = "/books/1",
        localCoverPath: String? = "file:///books/1/cover.jpg",
        currentTimeSeconds: Double = 0.0,
        progress: Double = 0.0,
        isFinished: Int = 0,
    ) = AudioBookEntity(
        id = "1",
        title = "Title",
        isDownloaded = isDownloaded,
        localPath = localPath,
        localCoverPath = localCoverPath,
        currentTimeSeconds = currentTimeSeconds,
        progress = progress,
        isFinished = isFinished,
    )

    @Test
    fun `preserves the local cover path across a sync`() {
        val remote = AudioBook(id = "1", coverPath = "https://server/cover", localCoverPath = null)

        val merged = mergeSyncedBook(remote, localEntity())

        assertTrue(merged.isDownloaded)
        assertEquals("/books/1", merged.localPath)
        assertEquals("file:///books/1/cover.jpg", merged.localCoverPath)
    }

    @Test
    fun `does not invent download state when the book is not downloaded locally`() {
        val remote = AudioBook(id = "1", coverPath = "https://server/cover")

        val merged = mergeSyncedBook(remote, localEntity(isDownloaded = 0, localCoverPath = null))

        assertFalse(merged.isDownloaded)
        assertNull(merged.localCoverPath)
    }

    @Test
    fun `returns the remote book unchanged when there is no local row`() {
        val remote = AudioBook(id = "1", coverPath = "https://server/cover")

        assertEquals(remote, mergeSyncedBook(remote, null))
    }

    @Test
    fun `keeps local progress when it is ahead of the server`() {
        val remote = AudioBook(id = "1", currentTime = 10.seconds)

        val merged = mergeSyncedBook(remote, localEntity(currentTimeSeconds = 50.0, progress = 0.5))

        assertEquals(50L, merged.currentTime.inWholeSeconds)
        assertEquals("file:///books/1/cover.jpg", merged.localCoverPath)
    }
}
