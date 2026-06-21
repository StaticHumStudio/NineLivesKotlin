package com.ninelivesaudio.app.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Persistence behaviour for the selectable theme. The default must stay NOIR so
 * existing users see no change, and legacy settings json without the field must
 * decode to NOIR rather than fail.
 */
class ThemeModeTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `default theme is NOIR`() {
        assertEquals(ThemeMode.NOIR, AppSettings().themeMode)
    }

    @Test
    fun `legacy settings json without themeMode decodes to NOIR`() {
        val legacy = """{"appMode":"LOCAL","serverUrl":"https://example.com"}"""
        val decoded = json.decodeFromString<AppSettings>(legacy)
        assertEquals(ThemeMode.NOIR, decoded.themeMode)
    }

    @Test
    fun `each theme round-trips through json`() {
        for (mode in ThemeMode.entries) {
            val decoded = json.decodeFromString<AppSettings>(
                json.encodeToString(AppSettings(themeMode = mode))
            )
            assertEquals(mode, decoded.themeMode)
        }
    }

    @Test
    fun `theme serializes by name`() {
        val encoded = json.encodeToString(AppSettings(themeMode = ThemeMode.AMOLED))
        assert(encoded.contains("\"themeMode\":\"AMOLED\"")) {
            "expected themeMode serialized by enum name, got: $encoded"
        }
    }
}
