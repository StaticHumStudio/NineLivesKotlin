package com.ninelivesaudio.app.service.download

import com.ninelivesaudio.app.domain.model.AudioFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
