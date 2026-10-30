package com.ninelivesaudio.app.service.download

import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.AudioFile
import com.ninelivesaudio.app.domain.model.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * Downloads made before 2.1.6 hold an empty track list, so they play in file
 * name order ("Chapter 10" before "Chapter 2") with no chapters (#35). The
 * server knows the real order, but its list is only taken when it matches the
 * files on disk exactly. Anything doubtful leaves the working download alone.
 */
class ManifestBackfillTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val serverChapters = listOf(
        Chapter(id = 0, start = 0.0, end = 60.0, title = "One"),
        Chapter(id = 1, start = 60.0, end = 120.0, title = "Two"),
    )

    /** Server order is 1, 2, 10 by index, listed in a different order. */
    private fun serverFiles(durationSeconds: Int = 60, size: Long = 3): List<AudioFile> = listOf(
        AudioFile(id = "a", ino = "a", index = 0, duration = durationSeconds.seconds, filename = "Chapter 1.mp3", size = size),
        AudioFile(id = "c", ino = "c", index = 2, duration = durationSeconds.seconds, filename = "Chapter 10.mp3", size = size),
        AudioFile(id = "b", ino = "b", index = 1, duration = durationSeconds.seconds, filename = "Chapter 2.mp3", size = size),
    )

    private fun folderWithTracks(vararg names: String = arrayOf("Chapter 1.mp3", "Chapter 10.mp3", "Chapter 2.mp3")): File {
        val dir = tempFolder.newFolder()
        names.forEach { File(dir, it).writeBytes(byteArrayOf(1, 2, 3)) }
        return dir
    }

    @Test
    fun `a legacy download takes the server's track order and chapters when every track is on disk`() {
        val dir = folderWithTracks()

        val decision = decideManifestBackfill(dir, serverFiles(), serverChapters)

        assertTrue(decision is ManifestBackfill.Accept)
        decision as ManifestBackfill.Accept
        assertEquals(listOf("Chapter 1.mp3", "Chapter 2.mp3", "Chapter 10.mp3"), decision.audioFiles.map { it.filename })
        assertEquals(serverChapters, decision.chapters)
    }

    @Test
    fun `the accepted tracks point at the files on disk`() {
        val dir = folderWithTracks()

        val decision = decideManifestBackfill(dir, serverFiles(), serverChapters) as ManifestBackfill.Accept

        decision.audioFiles.forEach {
            val file = File(it.localPath!!)
            assertTrue(file.isFile)
            assertEquals(dir.canonicalFile, file.canonicalFile.parentFile)
        }
    }

    @Test
    fun `a different number of files on disk keeps the download as it is`() {
        val dir = folderWithTracks("Chapter 1.mp3", "Chapter 10.mp3", "Chapter 2.mp3", "Extra.mp3")

        assertTrue(decideManifestBackfill(dir, serverFiles(), serverChapters) is ManifestBackfill.Reject)
    }

    @Test
    fun `a server track missing from disk keeps the download as it is`() {
        val dir = folderWithTracks("Chapter 1.mp3", "Chapter 2.mp3", "Other.mp3")

        assertTrue(decideManifestBackfill(dir, serverFiles(), serverChapters) is ManifestBackfill.Reject)
    }

    @Test
    fun `a file whose size differs from the server's keeps the download as it is`() {
        val dir = folderWithTracks()

        assertTrue(decideManifestBackfill(dir, serverFiles(size = 4), serverChapters) is ManifestBackfill.Reject)
    }

    @Test
    fun `a server size of zero is not a mismatch`() {
        val dir = folderWithTracks()

        assertTrue(decideManifestBackfill(dir, serverFiles(size = 0), serverChapters) is ManifestBackfill.Accept)
    }

    @Test
    fun `a server list without track durations keeps the download as it is`() {
        val dir = folderWithTracks()

        assertTrue(decideManifestBackfill(dir, serverFiles(durationSeconds = 0), serverChapters) is ManifestBackfill.Reject)
    }

    @Test
    fun `a server list with no tracks keeps the download as it is`() {
        val dir = folderWithTracks()

        assertTrue(decideManifestBackfill(dir, emptyList(), serverChapters) is ManifestBackfill.Reject)
    }

    @Test
    fun `a legacy folder shared by two editions is rejected`() {
        val dir = folderWithTracks("Chapter 1.mp3", "Chapter 10.mp3", "Chapter 2.mp3", "B 1.mp3", "B 2.mp3")

        assertTrue(decideManifestBackfill(dir, serverFiles(), serverChapters) is ManifestBackfill.Reject)
    }

    @Test
    fun `a server name that leaves the folder is rejected`() {
        val dir = folderWithTracks("a.mp3")
        val sneaky = listOf(AudioFile(id = "a", ino = "a", index = 0, duration = 60.seconds, filename = "..", size = 3))

        assertTrue(decideManifestBackfill(dir, sneaky, serverChapters) is ManifestBackfill.Reject)
    }

    @Test
    fun `part files, the cover and dot files are not counted as tracks`() {
        val dir = folderWithTracks()
        File(dir, "x.mp3.part").writeBytes(byteArrayOf(1))
        File(dir, "cover.jpg").writeBytes(byteArrayOf(1))
        File(dir, ".nomedia").writeBytes(byteArrayOf(1))

        assertTrue(decideManifestBackfill(dir, serverFiles(), serverChapters) is ManifestBackfill.Accept)
    }

    @Test
    fun `an empty file on disk is not a track`() {
        val dir = folderWithTracks()
        File(dir, "Chapter 2.mp3").writeBytes(ByteArray(0))

        assertTrue(decideManifestBackfill(dir, serverFiles(), serverChapters) is ManifestBackfill.Reject)
    }

    @Test
    fun `only a downloaded server book with an empty manifest needs the backfill`() {
        val manifest = serverFiles()
        val base = AudioBook(id = "b", isLocal = false, isDownloaded = true, localPath = "/d")

        assertTrue(needsManifestBackfill(base))
        assertFalse(needsManifestBackfill(base.copy(isLocal = true)))
        assertFalse(needsManifestBackfill(base.copy(isDownloaded = false)))
        assertFalse(needsManifestBackfill(base.copy(localPath = null)))
        assertFalse(needsManifestBackfill(base.copy(localPath = "")))
        assertFalse(needsManifestBackfill(base.copy(audioFiles = manifest)))
    }
}
