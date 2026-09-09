package com.ninelivesaudio.app.data.repository

import com.ninelivesaudio.app.data.local.entity.AudioBookEntity
import com.ninelivesaudio.app.data.local.converter.toEntity
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.AudioFile
import com.ninelivesaudio.app.domain.model.Chapter
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
        archivedAt: Long? = null,
    ) = AudioBookEntity(
        id = "1",
        title = "Title",
        isDownloaded = isDownloaded,
        localPath = localPath,
        localCoverPath = localCoverPath,
        currentTimeSeconds = currentTimeSeconds,
        progress = progress,
        isFinished = isFinished,
        archivedAt = archivedAt,
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
    fun `keeps the local archive flag across a sync`() {
        val remote = AudioBook(id = "1", coverPath = "https://server/cover") // archivedAt = null

        val merged = mergeSyncedBook(remote, localEntity(archivedAt = 123L))

        assertEquals(123L, merged.archivedAt)
        assertTrue(merged.isArchived)
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

    @Test
    fun `sparse sync retains downloaded canonical tracks and chapters`() {
        val local = downloadedDetailedBook().toEntity()
        val sparseRemote = AudioBook(id = "1", title = "Server title")

        val merged = mergeSyncedBook(sparseRemote, local)

        assertEquals(downloadedDetailedBook().audioFiles, merged.audioFiles)
        assertEquals(downloadedDetailedBook().chapters, merged.chapters)
        assertTrue(merged.audioFiles.all { !it.localPath.isNullOrBlank() })
    }

    @Test
    fun `expanded sync replaces stale downloaded detail`() {
        val local = downloadedDetailedBook().toEntity()
        val expandedRemote = AudioBook(
            id = "1",
            title = "Server title",
            audioFiles = listOf(AudioFile(id = "new", index = 0, filename = "new.m4b")),
            chapters = listOf(Chapter(id = 3, start = 0.0, end = 20.0, title = "New chapter")),
        )

        val merged = mergeSyncedBook(expandedRemote, local)

        assertEquals(expandedRemote.audioFiles, merged.audioFiles)
        assertEquals(expandedRemote.chapters, merged.chapters)
    }

    @Test
    fun `expanded sync replaces detail for a non downloaded row`() {
        val remote = AudioBook(
            id = "1",
            title = "Server title",
            audioFiles = listOf(AudioFile(id = "remote", index = 4, filename = "remote.m4b")),
            chapters = listOf(Chapter(id = 4, start = 0.0, end = 30.0, title = "Remote chapter")),
        )
        val local = downloadedDetailedBook().toEntity().copy(isDownloaded = 0)

        val merged = mergeSyncedBook(remote, local)

        assertEquals(remote.audioFiles, merged.audioFiles)
        assertEquals(remote.chapters, merged.chapters)
    }

    private fun downloadedDetailedBook() = AudioBook(
        id = "1",
        title = "Downloaded title",
        isDownloaded = true,
        localPath = "/books/1",
        audioFiles = listOf(
            AudioFile(id = "two", index = 2, filename = "z-last.m4b", localPath = "/books/1/track-2.m4b"),
            AudioFile(id = "one", index = 1, filename = "a-first.m4b", localPath = "/books/1/track-1.m4b"),
        ),
        chapters = listOf(
            Chapter(id = 1, start = 0.0, end = 12.0, title = "One"),
            Chapter(id = 2, start = 12.0, end = 24.0, title = "Two"),
        ),
    )
}
