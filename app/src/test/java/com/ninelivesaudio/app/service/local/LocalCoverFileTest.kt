package com.ninelivesaudio.app.service.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Local covers are written to <coverDir>/<bookId>.jpg so they survive unscan. */
class LocalCoverFileTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `writes bytes to bookId dot jpg in the cover dir`() {
        val coverDir = tempFolder.newFolder("local_covers")
        val bytes = byteArrayOf(7, 8, 9)

        val file = writeLocalCoverFile(bytes, coverDir, "local_book_abc")

        assertEquals("local_book_abc.jpg", file.name)
        assertEquals(coverDir, file.parentFile)
        assertTrue(file.exists())
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun `creates the cover dir if missing`() {
        val coverDir = java.io.File(tempFolder.root, "local_covers")
        val file = writeLocalCoverFile(byteArrayOf(1), coverDir, "x")
        assertTrue(file.exists())
    }
}
