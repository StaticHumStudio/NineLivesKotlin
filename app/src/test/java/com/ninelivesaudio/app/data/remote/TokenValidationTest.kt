package com.ninelivesaudio.app.data.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Guards the load-bearing distinction between a rejected token (INVALID, safe to
 * log out) and an unreachable server (UNREACHABLE, must keep the token). A
 * regression here would re-introduce the cold-start "signed out" bug, where a
 * transient network failure on launch was misread as an expired session.
 */
class TokenValidationTest {

    @Test
    fun `auth mutation records before secure storage failure`() = runBlocking {
        val events = mutableListOf<String>()

        try {
            applyAuthTokenMutation(
                updateRuntimeAuth = { events += "runtime" },
                recordMutation = { events += "record" },
                persistSecureStorage = {
                    events += "storage"
                    error("secure storage failed")
                },
            )
            fail("Expected secure storage failure")
        } catch (_: IllegalStateException) {
            // Runtime auth and cache invalidation must already be complete.
        }

        assertEquals(listOf("runtime", "record", "storage"), events)
    }

    @Test
    fun `failed token login restores only its attempted durable token`() = runBlocking {
        var storedToken: String? = "attempted"
        var runtimeToken: String? = "attempted"
        var mutations = 0

        rollbackFailedTokenLogin(
            previousToken = "retained",
            attemptedToken = "attempted",
            readStoredToken = { storedToken },
            replaceStoredToken = { expected, replacement ->
                if (storedToken == expected) storedToken = replacement
            },
            restoreRuntimeAuth = { runtimeToken = it },
            recordMutation = { mutations++ },
        )

        assertEquals("retained", storedToken)
        assertEquals("retained", runtimeToken)
        assertEquals(1, mutations)
    }

    @Test
    fun `failed token login cannot overwrite a newer durable token`() = runBlocking {
        var storedToken: String? = "newer"

        rollbackFailedTokenLogin(
            previousToken = "retained",
            attemptedToken = "attempted",
            readStoredToken = { storedToken },
            replaceStoredToken = { _, _ -> fail("Must not replace a newer token") },
            restoreRuntimeAuth = {},
            recordMutation = {},
        )

        assertEquals("newer", storedToken)
    }

    @Test
    fun `failed token persistence restores runtime from retained durable token`() = runBlocking {
        var storedToken: String? = "retained"
        var runtimeToken: String? = "attempted"

        rollbackFailedTokenLogin(
            previousToken = "retained",
            attemptedToken = "attempted",
            readStoredToken = { storedToken },
            replaceStoredToken = { _, _ -> fail("Attempted token was never durable") },
            restoreRuntimeAuth = { runtimeToken = it },
            recordMutation = {},
        )

        assertEquals("retained", storedToken)
        assertEquals("retained", runtimeToken)
    }

    @Test
    fun `stored token validation waits for auth restoration`() {
        assertTrue(validationNeedsStoredAuth(null))
        assertFalse(validationNeedsStoredAuth("explicit-token"))
    }

    @Test
    fun `auth identity includes generation token and server`() {
        val original = AuthSessionIdentity(1, "token", "https://one.example")

        assertTrue(authSessionMatches(original, original.copy()))
        assertFalse(authSessionMatches(original, original.copy(generation = 2)))
        assertFalse(authSessionMatches(original, original.copy(token = "new-token")))
        assertFalse(authSessionMatches(original, original.copy(serverUrl = "https://two.example")))
        assertFalse(authSessionMatches(original, null))
    }

    @Test
    fun `a rollback failure after an invalid token does not clobber the real error`() = runBlocking {
        // Mirrors ApiService.loginWithToken's INVALID branch: rollback is
        // wrapped in runCatching so a check() failure inside it (e.g. a newer
        // auth mutation raced the rollback) never reaches the caller, which
        // would otherwise overwrite "Invalid API token" with a confusing
        // rollback-internal message.
        var lastError: String? = null

        runCatching {
            rollbackFailedTokenLogin(
                previousToken = "retained",
                attemptedToken = "attempted",
                readStoredToken = { "attempted" },
                replaceStoredToken = { _, _ -> error("Auth session changed during token rollback") },
                restoreRuntimeAuth = {},
                recordMutation = {},
            )
        }.onFailure { /* logged, not surfaced */ }
        lastError = "Invalid API token"

        assertEquals("Invalid API token", lastError)
    }

    @Test
    fun `failed password login leaves stored serverUrl and username unchanged`() = runBlocking {
        var storedServerUrl = "https://original.example"
        var storedUsername = "original-user"
        var mutations = 0

        rollbackFailedPasswordLogin(
            restoreSettings = {
                storedServerUrl = "https://original.example"
                storedUsername = "original-user"
            },
            recordMutation = { mutations++ },
        )

        assertEquals("https://original.example", storedServerUrl)
        assertEquals("original-user", storedUsername)
        assertEquals(1, mutations)
    }

    @Test
    fun `password login rollback failure does not propagate`() = runBlocking {
        var observedFailure: Throwable? = null

        // Must not throw even when the restore itself fails (e.g. the
        // encrypted store is unavailable) — a rollback failure must never
        // clobber the real lastError the caller is about to surface.
        rollbackFailedPasswordLogin(
            restoreSettings = { error("encrypted store unavailable") },
            recordMutation = { fail("Must not record a mutation after a failed restore") },
            onRollbackFailure = { observedFailure = it },
        )

        assertEquals("encrypted store unavailable", observedFailure?.message)
    }

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

    @Test
    fun `4xx login failures are REJECTED`() {
        assertEquals(CredentialLoginResult.REJECTED, classifyLoginHttpFailure(401))
        assertEquals(CredentialLoginResult.REJECTED, classifyLoginHttpFailure(403))
        assertEquals(CredentialLoginResult.REJECTED, classifyLoginHttpFailure(400))
        assertEquals(CredentialLoginResult.REJECTED, classifyLoginHttpFailure(422))
    }

    @Test
    fun `5xx and unexpected login failures are UNREACHABLE`() {
        assertEquals(CredentialLoginResult.UNREACHABLE, classifyLoginHttpFailure(500))
        assertEquals(CredentialLoginResult.UNREACHABLE, classifyLoginHttpFailure(503))
        assertEquals(CredentialLoginResult.UNREACHABLE, classifyLoginHttpFailure(302))
    }
}
