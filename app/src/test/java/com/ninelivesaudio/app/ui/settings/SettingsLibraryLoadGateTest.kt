package com.ninelivesaudio.app.ui.settings

import com.ninelivesaudio.app.data.remote.TokenValidationResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
    fun `validation dispatcher clears only invalid and preserves unreachable`() = runBlocking {
        suspend fun eventsFor(
            result: TokenValidationResult,
            clearSucceeds: Boolean = true,
        ): List<String> {
            val events = mutableListOf<String>()
            dispatchStoredValidation(
                result = result,
                onValid = { events += "valid" },
                clearInvalidSession = {
                    events += "clear"
                    clearSucceeds
                },
                onInvalidCleared = { events += "invalid" },
                onUnreachable = { events += "unreachable" },
            )
            return events
        }

        assertEquals(listOf("valid"), eventsFor(TokenValidationResult.VALID))
        assertEquals(listOf("clear", "invalid"), eventsFor(TokenValidationResult.INVALID))
        assertEquals(listOf("clear"), eventsFor(TokenValidationResult.INVALID, clearSucceeds = false))
        assertEquals(listOf("unreachable"), eventsFor(TokenValidationResult.UNREACHABLE))
    }

    @Test
    fun `stale session blocks every validation verdict`() {
        TokenValidationResult.entries.forEach { result ->
            assertFalse(
                shouldApplyStoredValidation(
                    result = result,
                    uiGenerationUnchanged = false,
                    authSessionCurrent = true,
                ),
            )
            assertFalse(
                shouldApplyStoredValidation(
                    result = result,
                    uiGenerationUnchanged = true,
                    authSessionCurrent = false,
                ),
            )
            assertTrue(
                shouldApplyStoredValidation(
                    result = result,
                    uiGenerationUnchanged = true,
                    authSessionCurrent = true,
                ),
            )
        }
    }

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
