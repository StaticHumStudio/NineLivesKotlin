package com.ninelivesaudio.app.service.download

import com.ninelivesaudio.app.data.local.converter.toDomain
import com.ninelivesaudio.app.data.local.converter.toEntity
import com.ninelivesaudio.app.data.remote.dto.ApiChapter
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.AudioFile
import com.ninelivesaudio.app.domain.model.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * A finished download has to leave its track list and chapters in the book row.
 * Library sync stores an empty manifest, and without one offline playback sorts
 * the files by name, so "Chapter 10.mp3" plays before "Chapter 2.mp3".
 */
class DownloadCompletionManifestTest {

    private val tracks = listOf(
        AudioFile(id = "a", ino = "a", index = 0, duration = 60.seconds, filename = "Chapter 2.mp3"),
        AudioFile(id = "b", ino = "b", index = 1, duration = 60.seconds, filename = "Chapter 10.mp3"),
    )
    private val chapters = listOf(
        Chapter(id = 0, start = 0.0, end = 60.0, title = "Two"),
        Chapter(id = 1, start = 60.0, end = 120.0, title = "Ten"),
    )

    /** What a library sync leaves behind: no tracks, no chapters. */
    private val syncedRow = AudioBook(id = "book", title = "Book", author = "Author", currentTime = 42.seconds)

    @Test
    fun `completion saves the downloaded tracks and chapters over an empty row`() {
        val saved = completedDownloadBook(
            row = syncedRow,
            downloaded = syncedRow.copy(audioFiles = tracks, chapters = chapters),
            localPath = "/downloads/Author - Book",
            localCoverUri = "file:///downloads/Author - Book/cover.jpg",
        )

        assertEquals(tracks, saved.audioFiles)
        assertEquals(chapters, saved.chapters)
        assertTrue(saved.isDownloaded)
        assertEquals("/downloads/Author - Book", saved.localPath)
        assertEquals("file:///downloads/Author - Book/cover.jpg", saved.localCoverPath)
    }

    @Test
    fun `completion keeps the row's own progress`() {
        val saved = completedDownloadBook(
            row = syncedRow,
            downloaded = syncedRow.copy(currentTime = 0.seconds, audioFiles = tracks),
            localPath = "/d",
            localCoverUri = null,
        )

        assertEquals(42.seconds, saved.currentTime)
    }

    @Test
    fun `a download with no chapters leaves the row's chapters alone`() {
        val saved = completedDownloadBook(
            row = syncedRow.copy(chapters = chapters),
            downloaded = syncedRow.copy(audioFiles = tracks),
            localPath = "/d",
            localCoverUri = null,
        )

        assertEquals(chapters, saved.chapters)
    }

    @Test
    fun `a failed cover save keeps the cover the row already had`() {
        val saved = completedDownloadBook(
            row = syncedRow.copy(localCoverPath = "file:///old/cover.jpg"),
            downloaded = syncedRow.copy(audioFiles = tracks),
            localPath = "/d",
            localCoverUri = null,
        )

        assertEquals("file:///old/cover.jpg", saved.localCoverPath)
    }

    @Test
    fun `the saved manifest plays in server order offline, not name order`() {
        val saved = completedDownloadBook(
            row = syncedRow,
            downloaded = syncedRow.copy(audioFiles = tracks.reversed()),
            localPath = "/d",
            localCoverUri = null,
        )
        // Through the Room column and back, the way PlaybackManager reads it.
        val reloaded = saved.toEntity().toDomain()
        val ordered = reloaded.audioFiles.sortedBy { it.index }

        assertEquals(listOf("Chapter 2.mp3", "Chapter 10.mp3"), resolveDownloadFileNames(ordered))
    }

    @Test
    fun `saved chapters survive the Room column`() {
        val saved = completedDownloadBook(
            row = syncedRow,
            downloaded = syncedRow.copy(audioFiles = tracks, chapters = chapters),
            localPath = "/d",
            localCoverUri = null,
        )

        assertEquals(chapters, saved.toEntity().toDomain().chapters)
    }

    @Test
    fun `server chapters map with the same filter and fallback title as the item mapping`() {
        val mapped = toDomainChapters(
            listOf(
                ApiChapter(id = 0, start = 0.0, end = 10.0, title = "Opening"),
                ApiChapter(id = 1, start = 10.0, end = 10.0, title = "Empty"),
                ApiChapter(id = 2, start = -1.0, end = 5.0, title = "Negative"),
                ApiChapter(id = 3, start = 10.0, end = 20.0, title = " "),
            ),
        )

        assertEquals(listOf("Opening", "Chapter 3"), mapped.map { it.title })
    }
}
