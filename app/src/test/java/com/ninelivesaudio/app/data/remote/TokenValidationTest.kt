package com.ninelivesaudio.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the load-bearing distinction between a rejected token (INVALID, safe to
 * log out) and an unreachable server (UNREACHABLE, must keep the token). A
 * regression here would re-introduce the cold-start "signed out" bug, where a
 * transient network failure on launch was misread as an expired session.
 */
class TokenValidationTest {

    @Test
    fun `2xx codes are VALID`() {
        assertEquals(TokenValidationResult.VALID, classifyValidationStatus(200))
        assertEquals(TokenValidationResult.VALID, classifyValidationStatus(204))
        assertEquals(TokenValidationResult.VALID, classifyValidationStatus(299))
    }

    @Test
    fun `401 and 403 are INVALID`() {
        assertEquals(TokenValidationResult.INVALID, classifyValidationStatus(401))
        assertEquals(TokenValidationResult.INVALID, classifyValidationStatus(403))
    }

    @Test
    fun `server errors and unexpected codes are UNREACHABLE not INVALID`() {
        // 5xx must never log the user out — the server is up but struggling.
        assertEquals(TokenValidationResult.UNREACHABLE, classifyValidationStatus(500))
        assertEquals(TokenValidationResult.UNREACHABLE, classifyValidationStatus(502))
        assertEquals(TokenValidationResult.UNREACHABLE, classifyValidationStatus(503))
        // Other unexpected codes are likewise not an auth verdict.
        assertEquals(TokenValidationResult.UNREACHABLE, classifyValidationStatus(429))
        assertEquals(TokenValidationResult.UNREACHABLE, classifyValidationStatus(418))
    }
}
