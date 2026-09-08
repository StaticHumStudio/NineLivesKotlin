package com.ninelivesaudio.app.service.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException

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

    @Test
    fun `stream copy keeps multi-buffer bytes and target filename`() {
        val coverDir = tempFolder.newFolder("local_covers")
        val bytes = ByteArray(24 * 1024 + 17) { (it * 31).toByte() }

        val file = streamToLocalCoverFile(ByteArrayInputStream(bytes), coverDir, "local_book_abc")

        assertNotNull(file)
        assertEquals("local_book_abc.jpg", file!!.name)
        assertArrayEquals(bytes, file.readBytes())
        assertNoTemporaryFiles(coverDir)
    }

    @Test
    fun `stream copy replaces an existing cover only after success`() {
        val coverDir = tempFolder.newFolder("local_covers")
        val target = writeLocalCoverFile(byteArrayOf(1, 2, 3), coverDir, "book")
        val replacement = byteArrayOf(4, 5, 6)

        val file = streamToLocalCoverFile(ByteArrayInputStream(replacement), coverDir, "book")

        assertEquals(target, file)
        assertArrayEquals(replacement, target.readBytes())
        assertNoTemporaryFiles(coverDir)
    }

    @Test
    fun `empty stream preserves existing cover`() {
        val coverDir = tempFolder.newFolder("local_covers")
        val target = writeLocalCoverFile(byteArrayOf(1, 2, 3), coverDir, "book")

        val file = streamToLocalCoverFile(ByteArrayInputStream(byteArrayOf()), coverDir, "book")

        assertNull(file)
        assertArrayEquals(byteArrayOf(1, 2, 3), target.readBytes())
        assertNoTemporaryFiles(coverDir)
    }

    @Test
    fun `read failure preserves existing cover and removes partial temp`() {
        val coverDir = tempFolder.newFolder("local_covers")
        val target = writeLocalCoverFile(byteArrayOf(1, 2, 3), coverDir, "book")

        val file = streamToLocalCoverFile(PartialReadFailureInputStream(), coverDir, "book")

        assertNull(file)
        assertArrayEquals(byteArrayOf(1, 2, 3), target.readBytes())
        assertNoTemporaryFiles(coverDir)
    }

    @Test
    fun `write failure preserves existing cover and removes partial temp`() {
        val coverDir = tempFolder.newFolder("local_covers")
        val target = writeLocalCoverFile(byteArrayOf(1, 2, 3), coverDir, "book")

        val file = streamToLocalCoverFile(
            input = ByteArrayInputStream(byteArrayOf(4, 5, 6)),
            coverDir = coverDir,
            bookId = "book",
            openOutputStream = { PartialWriteFailureOutputStream() },
        )

        assertNull(file)
        assertArrayEquals(byteArrayOf(1, 2, 3), target.readBytes())
        assertNoTemporaryFiles(coverDir)
    }

    @Test
    fun `cancellation preserves existing cover and removes partial temp`() {
        val coverDir = tempFolder.newFolder("local_covers")
        val target = writeLocalCoverFile(byteArrayOf(1, 2, 3), coverDir, "book")

        val file = streamToLocalCoverFile(CancelledInputStream(), coverDir, "book")

        assertNull(file)
        assertArrayEquals(byteArrayOf(1, 2, 3), target.readBytes())
        assertNoTemporaryFiles(coverDir)
    }

    @Test
    fun `directory setup failure closes the source stream`() {
        val blockedParent = tempFolder.newFile("not_a_directory")
        val coverDir = java.io.File(blockedParent, "local_covers")
        val input = CloseTrackingInputStream()

        val file = streamToLocalCoverFile(input, coverDir, "book")

        assertNull(file)
        assertTrue(input.closed)
        assertFalse(coverDir.exists())
    }

    private fun assertNoTemporaryFiles(coverDir: java.io.File) {
        assertFalse(coverDir.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
    }

    private class PartialReadFailureInputStream : InputStream() {
        private var hasReadByte = false

        override fun read(): Int {
            if (!hasReadByte) {
                hasReadByte = true
                return 1
            }
            throw IOException("provider read failed")
        }
    }

    private class PartialWriteFailureOutputStream : OutputStream() {
        private var bytesWritten = 0

        override fun write(oneByte: Int) {
            if (bytesWritten++ == 0) return
            throw IOException("disk write failed")
        }
    }

    private class CancelledInputStream : InputStream() {
        private var hasReadByte = false

        override fun read(): Int {
            if (!hasReadByte) {
                hasReadByte = true
                return 1
            }
            throw CancellationException("scan cancelled")
        }
    }

    private class CloseTrackingInputStream : InputStream() {
        var closed = false

        override fun read(): Int = -1

        override fun close() {
            closed = true
        }
    }
}
