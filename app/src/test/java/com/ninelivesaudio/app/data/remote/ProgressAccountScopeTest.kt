package com.ninelivesaudio.app.data.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #118 final review: unsent progress is kept per signed-in Audiobookshelf
 * user, not per server address, so the same server reached by a LAN IP, a
 * domain or Tailscale keeps one owner, and another account never gets it.
 */
class ProgressAccountScopeTest {

    @Test
    fun `a known user is used as is, with no call to the server`() = runBlocking {
        var asked = 0
        val scope = ProgressAccountScope({ " user-a " }, { asked++; "other" })
        assertEquals("user-a", scope.known())
        assertEquals("user-a", scope.resolve())
        assertEquals(0, asked)
    }

    @Test
    fun `an unknown user (token login, 2_1 upgrade) is asked of the server`() = runBlocking {
        val scope = ProgressAccountScope({ null }, { "user-a" })
        assertEquals("", scope.known())
        assertEquals("user-a", scope.resolve())
    }

    @Test
    fun `no answer is no owner, so nothing is delivered for anyone`() = runBlocking {
        assertEquals("", ProgressAccountScope({ null }, { null }).resolve())
        assertEquals("", ProgressAccountScope({ "  " }, { "" }).resolve())
    }
}
