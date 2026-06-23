package com.ninelivesaudio.app.service.download

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A downloaded book persists its cover next to its audio so the cover renders
 * offline. The bytes must land in cover.jpg inside the book's download dir.
 */
class DownloadCoverTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `writes cover bytes to cover dot jpg in the download dir`() {
        val dir = tempFolder.newFolder("Author - Title")
        val bytes = byteArrayOf(1, 2, 3, 4)

        val file = writeCoverFile(bytes, dir)

        assertEquals("cover.jpg", file.name)
        assertEquals(dir, file.parentFile)
        assertTrue(file.exists())
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun `creates the download dir if it does not exist yet`() {
        val dir = File(tempFolder.root, "nested/Author - Title")

        val file = writeCoverFile(byteArrayOf(9), dir)

        assertTrue(file.exists())
        assertArrayEquals(byteArrayOf(9), file.readBytes())
    }
}
