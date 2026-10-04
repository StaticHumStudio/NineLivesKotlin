package com.ninelivesaudio.app.service.download

import com.ninelivesaudio.app.domain.model.AudioFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Pure decision logic lifted out of the download streaming loop so it can be
 * unit tested without WorkManager, Room, Retrofit, or the filesystem. The
 * streaming orchestration itself (DownloadEngine.download) is verified by the
 * on-device survival pass, consistent with the rest of this app's I/O.
 */
class DownloadPoliciesTest {

    // ─── sanitizeDownloadFileName ─────────────────────────────────────────

    @Test
    fun `sanitize replaces illegal path characters with underscore`() {
        assertEquals("a_b_c_d", sanitizeDownloadFileName("a/b:c?d"))
    }

    @Test
    fun `sanitize collapses whitespace and trims`() {
        assertEquals("foo bar", sanitizeDownloadFileName("  foo   bar  "))
    }

    @Test
    fun `sanitize caps length at 200`() {
        assertEquals(200, sanitizeDownloadFileName("x".repeat(300)).length)
    }

    // ─── downloadFolderName ───────────────────────────────────────────────

    @Test
    fun `folder name combines author and title`() {
        assertEquals("Jane Doe - My Book", downloadFolderName("Jane Doe", "My Book", "id1"))
    }

    @Test
    fun `folder name omits the Unknown Author placeholder`() {
        assertEquals("My Book", downloadFolderName("Unknown Author", "My Book", "id1"))
    }

    @Test
    fun `folder name omits a blank author`() {
        assertEquals("My Book", downloadFolderName("", "My Book", "id1"))
    }

    @Test
    fun `folder name falls back to id when author and title are blank`() {
        assertEquals("id1", downloadFolderName("", "", "id1"))
    }

    @Test
    fun `folder name sanitizes illegal characters in author and title`() {
        assertEquals("A_B - C_D", downloadFolderName("A/B", "C:D", "id1"))
    }

    // ─── itemDownloadFolderName (#49) ─────────────────────────────────────

    @Test
    fun `new folder name puts the title first and the item id in brackets at the end`() {
        assertEquals(
            "Jane Doe - My Book [b7c1d2e3]",
            itemDownloadFolderName("Jane Doe", "My Book", "5f0c2a9e-3b1d-4c8e-9a7f-1e2db7c1d2e3"),
        )
    }

    @Test
    fun `two editions of one title get different folders`() {
        val first = itemDownloadFolderName("Herman Melville", "Moby-Dick", "li_8gch9ve09orgn4fdz8")
        val second = itemDownloadFolderName("Herman Melville", "Moby-Dick", "li_2kq0x7mbn1aa3plw4c")
        assertTrue(first != second)
        assertTrue(first != downloadFolderName("Herman Melville", "Moby-Dick", "li_8gch9ve09orgn4fdz8"))
    }

    @Test
    fun `new folder name is stable for the same item`() {
        assertEquals(
            itemDownloadFolderName("A", "B", "li_8gch9ve09orgn4fdz8"),
            itemDownloadFolderName("A", "B", "li_8gch9ve09orgn4fdz8"),
        )
    }

    @Test
    fun `short id keeps only letters and digits from the id`() {
        assertEquals("abc12345", shortItemId("x/y:z*abc-123?45"))
        assertEquals("ab", shortItemId("a/b"))
    }

    @Test
    fun `short id falls back to a hash when the id has no letters or digits`() {
        assertEquals(Integer.toHexString("--".hashCode()), shortItemId("--"))
        assertEquals("[${shortItemId("")}]", itemDownloadFolderName("", "", ""))
    }

    @Test
    fun `new folder name sanitizes odd characters`() {
        val name = itemDownloadFolderName("A/B", "C:D*E?\"F<G>|H\\I", "li_8gch9ve09orgn4fdz8")
        assertEquals("A_B - C_D_E__F_G__H_I [rgn4fdz8]", name)
    }

    @Test
    fun `new folder name without author or title is just the id`() {
        assertEquals("[rgn4fdz8]", itemDownloadFolderName("Unknown Author", "  ", "li_8gch9ve09orgn4fdz8"))
    }

    @Test
    fun `a long title is cut to fit and the id survives`() {
        val name = itemDownloadFolderName("Author", "x".repeat(400), "li_8gch9ve09orgn4fdz8")
        assertTrue(name.endsWith(" [rgn4fdz8]"))
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= MAX_FOLDER_NAME_BYTES)
    }

    @Test
    fun `a long multibyte title fits the byte limit without splitting a character`() {
        // Three bytes each in UTF-8, plus one emoji (a surrogate pair, four bytes) at every step.
        val name = itemDownloadFolderName("作者", "長い題名📚".repeat(60), "li_8gch9ve09orgn4fdz8")
        assertTrue(name.endsWith(" [rgn4fdz8]"))
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= MAX_FOLDER_NAME_BYTES)
        assertEquals(name, String(name.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
    }

    // ─── estimateTotalBytes ───────────────────────────────────────────────

    @Test
    fun `total bytes sums file sizes when present`() {
        val files = listOf(AudioFile(size = 100), AudioFile(size = 250))
        assertEquals(350L, estimateTotalBytes(files))
    }

    @Test
    fun `total bytes estimates from duration when sizes are zero`() {
        val files = listOf(
            AudioFile(size = 0, duration = 10.seconds),
            AudioFile(size = 0, duration = 5.seconds),
        )
        assertEquals(15L * 16_000L, estimateTotalBytes(files))
    }

    // ─── shouldPersistProgress ────────────────────────────────────────────

    @Test
    fun `persist when byte delta reaches the threshold`() {
        assertTrue(shouldPersistProgress(bytesDelta = MIN_PROGRESS_DELTA_BYTES, timeDeltaMs = 0))
    }

    @Test
    fun `persist when time delta reaches the threshold`() {
        assertTrue(shouldPersistProgress(bytesDelta = 0, timeDeltaMs = MIN_PROGRESS_UPDATE_INTERVAL_MS))
    }

    @Test
    fun `do not persist below both thresholds`() {
        assertFalse(shouldPersistProgress(bytesDelta = 1, timeDeltaMs = 1))
    }

    // ─── retryBackoffMs ───────────────────────────────────────────────────

    @Test
    fun `retry backoff is exponential starting at 10 seconds`() {
        assertEquals(10_000L, retryBackoffMs(1))
        assertEquals(20_000L, retryBackoffMs(2))
        assertEquals(40_000L, retryBackoffMs(3))
    }

    // ─── shouldSkipDownloadedFile ─────────────────────────────────────────

    @Test
    fun `skip an existing non-empty file`() {
        assertTrue(shouldSkipDownloadedFile(exists = true, length = 1))
    }

    @Test
    fun `do not skip a missing file`() {
        assertFalse(shouldSkipDownloadedFile(exists = false, length = 0))
    }

    @Test
    fun `do not skip an existing but empty file`() {
        assertFalse(shouldSkipDownloadedFile(exists = true, length = 0))
    }

    // ─── resolveDownloadFileNames ─────────────────────────────────────────

    @Test
    fun resolveDownloadFileNames_leavesUniqueNamesAlone() {
        val files = listOf(audioFile("one.mp3", index = 0), audioFile("two.mp3", index = 1))
        assertEquals(listOf("one.mp3", "two.mp3"), resolveDownloadFileNames(files))
    }

    @Test
    fun resolveDownloadFileNames_disambiguatesCollisionsByPosition() {
        val files = listOf(
            audioFile("disc/one.mp3", index = 0),
            audioFile("disc:one.mp3", index = 1),
            audioFile("three.mp3", index = 2),
        )
        val names = resolveDownloadFileNames(files)
        assertEquals(3, names.toSet().size)
        assertEquals("three.mp3", names[2])
        assertTrue(names[0].endsWith("_1.mp3"))
        assertTrue(names[1].endsWith("_2.mp3"))
    }

    @Test
    fun resolveDownloadFileNames_zeroAndOneIndexCollisionStayDistinct() {
        // Regression for the PR #33 suffix bug: index 0 fell back to position+1 == 1,
        // index 1 produced 1, and both files landed on the same name.
        val files = listOf(audioFile("same.mp3", index = 0), audioFile("same.mp3", index = 1))
        val names = resolveDownloadFileNames(files)
        assertNotEquals(names[0], names[1])
    }

    @Test
    fun resolveDownloadFileNames_blankFilenameUsesTrackNumber() {
        val files = listOf(audioFile("", index = 0))
        assertEquals(listOf("track_1"), resolveDownloadFileNames(files))
    }

    @Test
    fun resolveDownloadFileNames_suffixNeverCollidesWithAnotherTrack() {
        // A generated suffix must not land on a name another track already owns.
        val files = listOf(
            audioFile("a.mp3", index = 0),
            audioFile("a.mp3", index = 1),
            audioFile("a_1.mp3", index = 2),
        )
        val names = resolveDownloadFileNames(files)
        assertEquals(3, names.toSet().size)
        assertEquals("a_1.mp3", names[2])
    }

    private fun audioFile(filename: String, index: Int) = AudioFile(
        id = "f$index", ino = "i$index", index = index,
        duration = 1.seconds, filename = filename,
    )
}
