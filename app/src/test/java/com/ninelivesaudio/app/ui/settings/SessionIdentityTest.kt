package com.ninelivesaudio.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionIdentityTest {

    @Test
    fun `retained session keeps the stored server identity`() {
        // The typed server never answered; the token that validated belongs to
        // the stored server. Persisting the typed URL would pair that token
        // with the wrong host, and a later 401 from it would clear a valid
        // credential.
        val (serverUrl, username) = sessionIdentityForOutcome(
            retained = true,
            typedServerUrl = "https://server-b.example",
            typedUsername = "jeff",
            storedServerUrl = "https://server-a.example",
            storedUsername = "jeff",
        )
        assertEquals("https://server-a.example", serverUrl)
        assertEquals("jeff", username)
    }

    @Test
    fun `new session owns the typed identity`() {
        val (serverUrl, username) = sessionIdentityForOutcome(
            retained = false,
            typedServerUrl = "https://server-b.example",
            typedUsername = "jeff",
            storedServerUrl = "https://server-a.example",
            storedUsername = "old-name",
        )
        assertEquals("https://server-b.example", serverUrl)
        assertEquals("jeff", username)
    }
}
