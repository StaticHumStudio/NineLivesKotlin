package com.ninelivesaudio.app.domain.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `legacy settings without a changelog version decode to the blank default`() {
        val legacy = """{"appMode":"LOCAL"}"""
        val decoded = json.decodeFromString<AppSettings>(legacy)

        assertEquals("", decoded.lastSeenChangelogVersion)
    }

    @Test
    fun `last seen changelog version round-trips through json`() {
        val original = AppSettings(lastSeenChangelogVersion = "2.0.1")
        val decoded = json.decodeFromString<AppSettings>(json.encodeToString(original))

        assertEquals(original, decoded)
    }

    @Test
    fun `last sync record survives settings json round trip`() {
        val stored = """
            {
              "lastSync": {
                "result": "PARTIAL",
                "libraryCount": 2,
                "bookCount": 200,
                "failure": "items[Books]: timeout",
                "completedAtMs": 123456789,
                "serverUrl": "https://server.example"
              }
            }
        """.trimIndent()

        val decoded = json.decodeFromString<AppSettings>(stored)
        val roundTrip = json.parseToJsonElement(json.encodeToString(decoded)).jsonObject
        val lastSync = roundTrip["lastSync"]?.jsonObject

        assertEquals("PARTIAL", lastSync?.get("result")?.jsonPrimitive?.content)
        assertEquals("2", lastSync?.get("libraryCount")?.jsonPrimitive?.content)
        assertEquals("200", lastSync?.get("bookCount")?.jsonPrimitive?.content)
        assertEquals("items[Books]: timeout", lastSync?.get("failure")?.jsonPrimitive?.content)
        assertEquals("123456789", lastSync?.get("completedAtMs")?.jsonPrimitive?.content)
        assertEquals("https://server.example", lastSync?.get("serverUrl")?.jsonPrimitive?.content)
    }

    @Test
    fun `local mode uses only the local library selection`() {
        val settings = AppSettings(
            appMode = AppMode.LOCAL,
            selectedLibraryId = "server-library",
            selectedLocalLibraryId = "local-library",
        )

        assertEquals("local-library", settings.activeLibraryId)
    }

    @Test
    fun `local mode never falls back to a stale server selection`() {
        val settings = AppSettings(
            appMode = AppMode.LOCAL,
            selectedLibraryId = "server-library",
            selectedLocalLibraryId = null,
        )

        assertNull(settings.activeLibraryId)
    }

    @Test
    fun `server mode uses only the server library selection`() {
        val settings = AppSettings(
            appMode = AppMode.AUDIOBOOKSHELF,
            selectedLibraryId = "server-library",
            selectedLocalLibraryId = "local-library",
        )

        assertEquals("server-library", settings.activeLibraryId)
    }

    @Test
    fun `book scope matches mode and active library`() {
        val localSettings = AppSettings(
            appMode = AppMode.LOCAL,
            selectedLibraryId = "server-library",
            selectedLocalLibraryId = "local-library",
        )
        val serverSettings = localSettings.copy(appMode = AppMode.AUDIOBOOKSHELF)

        assertTrue(AudioBook(libraryId = "local-library", isLocal = true).isInActiveLibrary(localSettings))
        assertFalse(AudioBook(libraryId = "server-library", isLocal = false).isInActiveLibrary(localSettings))
        assertTrue(AudioBook(libraryId = "server-library", isLocal = false).isInActiveLibrary(serverSettings))
        assertFalse(AudioBook(libraryId = "local-library", isLocal = true).isInActiveLibrary(serverSettings))
    }

    @Test
    fun `orphan book is rejected when no active library is selected`() {
        val settings = AppSettings(
            appMode = AppMode.LOCAL,
            selectedLocalLibraryId = null,
        )

        assertFalse(AudioBook(libraryId = null, isLocal = true).isInActiveLibrary(settings))
    }
}
