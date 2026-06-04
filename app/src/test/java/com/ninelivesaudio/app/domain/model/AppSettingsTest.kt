package com.ninelivesaudio.app.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppSettingsTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `default mode is LOCAL`() {
        assertEquals(AppMode.LOCAL, AppSettings().appMode)
    }

    @Test
    fun `onboarding is incomplete by default`() {
        assertFalse(AppSettings().onboardingComplete)
    }

    @Test
    fun `legacy settings json without onboardingComplete decodes to false`() {
        val legacy = """{"appMode":"AUDIOBOOKSHELF","serverUrl":"https://example.com"}"""
        val decoded = json.decodeFromString<AppSettings>(legacy)
        assertEquals(AppMode.AUDIOBOOKSHELF, decoded.appMode)
        assertFalse(decoded.onboardingComplete)
    }

    @Test
    fun `onboardingComplete round-trips through json`() {
        val original = AppSettings(onboardingComplete = true)
        val decoded = json.decodeFromString<AppSettings>(json.encodeToString(original))
        assertEquals(true, decoded.onboardingComplete)
    }
}
