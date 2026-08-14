package com.ninelivesaudio.app.ui.settings

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import com.ninelivesaudio.app.data.remote.CredentialLoginResult
import com.ninelivesaudio.app.data.remote.TokenValidationResult

class PostLoginSyncTest {

    @Test
    fun `wrong password fails even with a stored token for the same user`() = runBlocking {
        var validated = false

        val outcome = resolvePasswordLogin(
            credentialLogin = { CredentialLoginResult.REJECTED },
            hadStoredToken = true,
            usernameMatchesStored = true,
            validateRetainedToken = {
                validated = true
                TokenValidationResult.VALID
            },
        )

        // A server-rejected password is a real answer — it must never fall
        // back to reporting success under the previous user's retained
        // session, regardless of what token happens to be stored.
        assertEquals(PasswordLoginOutcome.FAILED, outcome)
        assertFalse(validated)
    }

    @Test
    fun `unreachable attempt with matching username recovers with a valid retained token`() = runBlocking {
        val outcome = resolvePasswordLogin(
            credentialLogin = { CredentialLoginResult.UNREACHABLE },
            hadStoredToken = true,
            usernameMatchesStored = true,
            validateRetainedToken = { TokenValidationResult.VALID },
        )

        assertEquals(PasswordLoginOutcome.RETAINED_SESSION, outcome)
    }

    @Test
    fun `unreachable attempt rejects an invalid retained token`() = runBlocking {
        val outcome = resolvePasswordLogin(
            credentialLogin = { CredentialLoginResult.UNREACHABLE },
            hadStoredToken = true,
            usernameMatchesStored = true,
            validateRetainedToken = { TokenValidationResult.INVALID },
        )

        assertEquals(PasswordLoginOutcome.FAILED, outcome)
    }

    @Test
    fun `unreachable attempt for a different username never repairs someone else's session`() = runBlocking {
        var validated = false

        val outcome = resolvePasswordLogin(
            credentialLogin = { CredentialLoginResult.UNREACHABLE },
            hadStoredToken = true,
            usernameMatchesStored = false,
            validateRetainedToken = {
                validated = true
                TokenValidationResult.VALID
            },
        )

        assertEquals(PasswordLoginOutcome.FAILED, outcome)
        assertFalse(validated)
    }

    @Test
    fun `successful password login does not validate the previous token`() = runBlocking {
        var validated = false

        val outcome = resolvePasswordLogin(
            credentialLogin = { CredentialLoginResult.SUCCESS },
            hadStoredToken = true,
            usernameMatchesStored = true,
            validateRetainedToken = {
                validated = true
                TokenValidationResult.INVALID
            },
        )

        assertEquals(PasswordLoginOutcome.NEW_SESSION, outcome)
        assertFalse(validated)
    }

    @Test
    fun `session activation persists mode before loading libraries`() = runBlocking {
        val events = mutableListOf<String>()

        activateSessionAfterLogin(
            activateAudiobookshelf = { events += "mode" },
            loadLibraries = { events += "libraries" },
        )

        assertEquals(listOf("mode", "libraries"), events)
    }

    @Test
    fun `post-login sync runs before the reachability probe`() = runBlocking {
        val events = mutableListOf<String>()

        syncAfterLogin(
            syncNow = { events += "sync" },
            checkServerReachable = { events += "reachability" },
        )

        assertEquals(listOf("sync", "reachability"), events)
    }
}
