package com.ninelivesaudio.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteIdCodecTest {
    @Test fun `codec reverses arbitrary raw identifiers within the expected owner`() {
        val owner = "owner-key:/abs-a"
        val raw = "id: with / delimiters, unicode \uD83D\uDC7B, and spaces"

        val encoded = RemoteIdCodec.encode(owner, raw)

        assertEquals(raw, RemoteIdCodec.decodeForOwner(encoded, owner))
        assertTrue(encoded.startsWith(RemoteIdCodec.ownerPrefix(owner)))
        assertFalse(encoded.contains(raw))
        assertFalse(encoded.contains(owner))
    }

    @Test fun `codec rejects a different owner malformed envelope and legacy raw id`() {
        val encoded = RemoteIdCodec.encode("owner-a", "same-id")

        assertNull(RemoteIdCodec.decodeForOwner(encoded, "owner-b"))
        assertNull(RemoteIdCodec.decodeForOwner("nlr1:bad:payload:extra", "owner-a"))
        assertNull(RemoteIdCodec.decodeForOwner("same-id", "owner-a"))
    }
}
