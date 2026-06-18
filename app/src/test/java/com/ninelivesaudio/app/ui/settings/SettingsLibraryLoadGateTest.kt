package com.ninelivesaudio.app.ui.settings

import com.ninelivesaudio.app.data.remote.TokenValidationResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings must populate the cached library list whenever the stored token was
 * not explicitly rejected, so the (cache-backed, no-network) library selector
 * is available even when the server cannot be reached (airplane mode). Only an
 * INVALID verdict — where the user is logged out — skips the load, because then
 * there is nothing to select.
 */
class SettingsLibraryLoadGateTest {

    @Test
    fun `loads libraries when token is valid`() {
        assertTrue(shouldLoadCachedLibrariesAfterValidation(TokenValidationResult.VALID))
    }

    @Test
    fun `loads cached libraries when server is unreachable`() {
        // Airplane mode: this is the bug. The token is kept, so the cached
        // library selector must still appear.
        assertTrue(shouldLoadCachedLibrariesAfterValidation(TokenValidationResult.UNREACHABLE))
    }

    @Test
    fun `does not load libraries when token is invalid`() {
        // The user was logged out — there is nothing to select.
        assertFalse(shouldLoadCachedLibrariesAfterValidation(TokenValidationResult.INVALID))
    }
}
