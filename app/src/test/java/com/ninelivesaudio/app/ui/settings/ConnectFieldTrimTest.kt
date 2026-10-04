package com.ninelivesaudio.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The server URL and username fields used to trim on every keystroke, which ate
 * the space in "john smith" as it was typed. The fields now keep the raw text
 * and Connect trims the ends.
 */
class ConnectFieldTrimTest {

    @Test
    fun `connect trims the ends of the server URL and username`() {
        val state = SettingsViewModel.UiState(
            serverUrl = "  https://abs.example.com  ",
            username = " john smith ",
        ).trimmedForConnect()

        assertEquals("https://abs.example.com", state.serverUrl)
        assertEquals("john smith", state.username)
    }

    @Test
    fun `the space inside a username survives connect`() {
        val state = SettingsViewModel.UiState(username = "john smith").trimmedForConnect()

        assertEquals("john smith", state.username)
    }

    @Test
    fun `connect leaves the password exactly as typed`() {
        val state = SettingsViewModel.UiState(password = " pass word ").trimmedForConnect()

        assertEquals(" pass word ", state.password)
    }

    @Test
    fun `a whitespace only server URL counts as blank at connect`() {
        val state = SettingsViewModel.UiState(serverUrl = "   ").trimmedForConnect()

        assertEquals("", state.serverUrl)
    }

    @Test
    fun `the fingerprint host ignores spaces around the typed URL`() {
        assertEquals("abs.example.com", extractServerHost("  https://ABS.example.com:13378 "))
    }

    @Test
    fun `a blank or broken URL has no fingerprint host`() {
        assertNull(extractServerHost("   "))
        assertNull(extractServerHost("https://bad host"))
    }
}
