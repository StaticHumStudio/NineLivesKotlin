package com.ninelivesaudio.app.data.repository

import com.ninelivesaudio.app.data.local.entity.AudioBookEntity
import com.ninelivesaudio.app.data.local.entity.PlaybackProgressEntity
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

    @Test
    fun `the lean merge reads whole rows only for downloaded books missing tracks`() = kotlinx.coroutines.runBlocking {
        val downloaded = downloadedDetailedBook().toEntity()
        val plain = localEntity(isDownloaded = 0, localPath = null, localCoverPath = null, currentTimeSeconds = 50.0, progress = 0.5)
            .copy(id = "2")
        val fullRowLookups = mutableListOf<List<String>>()

        val merged = mergeSyncedBooksLean(
            remote = listOf(
                AudioBook(id = "1", title = "Server title"),
                AudioBook(id = "2", title = "Other"),
                AudioBook(id = "3", title = "New"),
            ),
            getMergeStates = { ids -> listOf(downloaded, plain).filter { it.id in ids }.map { it.toSyncMergeState() } },
            getFullRows = { ids -> fullRowLookups += ids; listOf(downloaded).filter { it.id in ids } },
            getProgressRows = { emptyList() },
        )

        assertEquals(listOf(listOf("1")), fullRowLookups)
        assertEquals(downloadedDetailedBook().audioFiles, merged[0].audioFiles)
        assertEquals(downloadedDetailedBook().chapters, merged[0].chapters)
        assertTrue(merged[0].isDownloaded)
        assertEquals(50.0, merged[1].currentTime.inWholeMilliseconds / 1000.0, 0.0001)
        assertEquals(AudioBook(id = "3", title = "New"), merged[2])
    }

    @Test
    fun `a book new to the cache takes the progress the pull already saved for it`() = kotlinx.coroutines.runBlocking {
        // The library list carries no progress. The pull fetched only 12
        // unknown books one by one, and saved rows for these two anyway.
        val progressLookups = mutableListOf<List<String>>()
        val merged = mergeSyncedBooksLean(
            remote = listOf(
                AudioBook(id = "listening", duration = 1000.seconds),
                AudioBook(id = "done", duration = 500.seconds),
                AudioBook(id = "untouched", duration = 300.seconds),
            ),
            getMergeStates = { emptyList() },
            getFullRows = { emptyList() },
            getProgressRows = { ids ->
                progressLookups += ids
                listOf(
                    PlaybackProgressEntity(audioBookId = "listening", positionSeconds = 250.0),
                    PlaybackProgressEntity(audioBookId = "done", positionSeconds = 0.0, isFinished = 1),
                ).filter { it.audioBookId in ids }
            },
        )

        assertEquals(listOf(listOf("listening", "done", "untouched")), progressLookups)
        assertEquals(250.0, merged[0].currentTime.inWholeMilliseconds / 1000.0, 0.0001)
        assertEquals(0.25, merged[0].progress, 0.0001)
        assertFalse(merged[0].isFinished)
        assertTrue(merged[1].isFinished)
        assertEquals(1.0, merged[1].progress, 0.0001)
        assertEquals(AudioBook(id = "untouched", duration = 300.seconds), merged[2])
    }

    @Test
    fun `the lean merge matches the whole-row merge`() {
        val local = localEntity(currentTimeSeconds = 90.0, progress = 0.4, isFinished = 0, archivedAt = 7L)
        val remote = AudioBook(
            id = "1",
            title = "Server",
            audioFiles = listOf(AudioFile(id = "a", index = 0, filename = "a.mp3")),
            chapters = listOf(Chapter(id = 1, start = 0.0, end = 5.0, title = "One")),
        )
        assertEquals(mergeSyncedBook(remote, local), mergeSyncedBook(remote, local.toSyncMergeState(), detail = null))
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
