package com.ninelivesaudio.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * #118 final review: unsent progress is kept per server, so a server switch
 * never delivers one server's listening to another.
 */
class ProgressServerScopeTest {

    @Test
    fun `one server typed two ways is one server`() {
        assertEquals(progressServerKey("https://abs.example.com"), progressServerKey("HTTPS://ABS.example.com:443/"))
        assertEquals("https://abs.example.com:443", progressServerKey("https://abs.example.com/"))
        assertEquals("http://10.0.0.5:13378/abs", progressServerKey("http://10.0.0.5:13378/abs/"))
    }

    @Test
    fun `different servers are different`() {
        assertNotEquals(progressServerKey("https://a.example.com"), progressServerKey("https://b.example.com"))
        assertNotEquals(progressServerKey("http://10.0.0.5:13378"), progressServerKey("http://10.0.0.5:13379"))
        assertNotEquals(progressServerKey("https://x.example.com/one"), progressServerKey("https://x.example.com/two"))
    }

    @Test
    fun `no server is no key`() {
        assertEquals("", progressServerKey(""))
        assertEquals("", progressServerKey("not a url"))
        assertEquals("", ProgressServerScope { "" }.current())
        assertEquals("https://a.example.com:443", ProgressServerScope { "https://a.example.com" }.current())
    }
}
