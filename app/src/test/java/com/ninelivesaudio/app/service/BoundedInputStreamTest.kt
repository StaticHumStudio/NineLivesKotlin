package com.ninelivesaudio.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * The byte cap on a cover download keeps the InputStream contract: a zero
 * length read is 0, never an early end of stream, and reads stop exactly at
 * the cap (#1).
 */
class BoundedInputStreamTest {

    private fun source(size: Int) = ByteArrayInputStream(ByteArray(size) { it.toByte() })

    @Test
    fun `a zero length read returns zero before and after the cap`() {
        val stream = BoundedInputStream(source(20), maxBytes = 10)
        assertEquals(0, stream.read(ByteArray(4), 0, 0))
        assertEquals(10, stream.read(ByteArray(64), 0, 64))
        assertEquals(0, stream.read(ByteArray(4), 0, 0))
    }

    @Test
    fun `bulk reads stop exactly at the cap`() {
        val stream = BoundedInputStream(source(20), maxBytes = 10)
        val buffer = ByteArray(64)
        assertEquals(10, stream.read(buffer, 0, 64))
        assertEquals(-1, stream.read(buffer, 0, 64))
        assertEquals(9.toByte(), buffer[9])
    }

    @Test
    fun `a read that crosses the cap is cut to what is left`() {
        val stream = BoundedInputStream(source(20), maxBytes = 10)
        val buffer = ByteArray(6)
        assertEquals(6, stream.read(buffer, 0, 6))
        assertEquals(4, stream.read(buffer, 0, 6))
        assertEquals(-1, stream.read(buffer, 0, 6))
    }

    @Test
    fun `single byte reads stop at the cap`() {
        val stream = BoundedInputStream(source(20), maxBytes = 3)
        assertEquals(0, stream.read())
        assertEquals(1, stream.read())
        assertEquals(2, stream.read())
        assertEquals(-1, stream.read())
    }

    @Test
    fun `a short source ends with -1 before the cap`() {
        val stream = BoundedInputStream(source(4), maxBytes = 10)
        assertEquals(4, stream.read(ByteArray(64), 0, 64))
        assertEquals(-1, stream.read(ByteArray(64), 0, 64))
        assertEquals(-1, stream.read())
    }

    @Test
    fun `close reaches the wrapped stream`() {
        var closed = false
        val inner = object : ByteArrayInputStream(ByteArray(4)) {
            override fun close() {
                closed = true
                super.close()
            }
        }
        BoundedInputStream(inner, maxBytes = 10).close()
        assertTrue(closed)
    }
}
