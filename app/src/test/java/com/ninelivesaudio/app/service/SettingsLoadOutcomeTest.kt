package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.domain.model.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the cold-start crash root cause: a storage read failure must degrade
 * to an error surface, not rethrow into an uncaught coroutine (NineLivesApp's
 * appScope, SettingsViewModel's viewModelScope) and not hang the UI forever
 * (isLoaded staying false).
 */
class SettingsLoadOutcomeTest {

    @Test
    fun `successful load is not degraded`() = runBlocking {
        val loaded = AppSettings(serverUrl = "https://server.example")

        val outcome = loadSettingsOrDegrade(
            retained = { AppSettings() },
            load = { loaded },
        )

        assertEquals(loaded, outcome.settings)
        assertFalse(outcome.storageUnavailable)
    }

    @Test
    fun `storage failure degrades instead of throwing, keeping retained settings`() = runBlocking {
        val retained = AppSettings(serverUrl = "https://retained.example")

        val outcome = loadSettingsOrDegrade(
            retained = { retained },
            load = { error("encrypted storage read failed") },
        )

        assertEquals(retained, outcome.settings)
        assertTrue(outcome.storageUnavailable)
    }

    @Test
    fun `storage failure never falls through to a fresh default value`() = runBlocking {
        // The retained value is whatever was already in memory (construction
        // defaults on a cold start) — loadSettingsOrDegrade must return that
        // exact reference, not synthesize a brand new AppSettings().
        val retained = AppSettings(serverUrl = "https://retained.example", username = "static")

        val outcome = loadSettingsOrDegrade(
            retained = { retained },
            load = { error("encrypted storage read failed") },
        )

        assertEquals("https://retained.example", outcome.settings.serverUrl)
        assertEquals("static", outcome.settings.username)
    }

    @Test
    fun `failure callback observes the thrown exception`() = runBlocking {
        var observed: Exception? = null

        loadSettingsOrDegrade(
            retained = { AppSettings() },
            load = { error("boom") },
            onFailure = { observed = it },
        )

        assertEquals("boom", observed?.message)
    }
}
