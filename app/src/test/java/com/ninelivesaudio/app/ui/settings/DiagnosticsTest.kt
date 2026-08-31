package com.ninelivesaudio.app.ui.settings

import com.ninelivesaudio.app.domain.model.AppMode
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bug report body is the only thing a user can hand over. A report that
 * lists EQ state and sleep-timer shake but not the app mode or the selected
 * library cannot answer "my shelf won't sync", which is the most common report
 * there is. These tests pin the fields that actually narrow it down.
 */
class DiagnosticsTest {

    private fun snapshot(
        appMode: AppMode = AppMode.AUDIOBOOKSHELF,
        serverLibraryId: String? = "lib_abc",
        localLibraryId: String? = null,
        libraryCount: Int = 2,
        bookCount: Int = 431,
        activeLibraryBookCount: Int = 431,
        lastSync: String = "2 libraries, 431 books, 3m ago",
    ) = DiagnosticsSnapshot(
        appVersion = "v2.0.1 (201)",
        buildType = "release",
        device = "samsung SM-S938U",
        androidVersion = "16 (API 36)",
        connection = "Connected to https://example.test/abs",
        appMode = appMode,
        serverLibraryId = serverLibraryId,
        localLibraryId = localLibraryId,
        libraryCount = libraryCount,
        bookCount = bookCount,
        activeLibraryBookCount = activeLibraryBookCount,
        lastSync = lastSync,
        eqEnabled = false,
        autoRewind = "smart (15s)",
        sleepMotion = true,
        sleepShake = true,
    )

    @Test
    fun `names the source mode`() {
        assertTrue(renderDiagnostics(snapshot()).contains("Source: Audiobookshelf"))
    }

    @Test
    fun `local mode says the shelf is not being synced even while a server is connected`() {
        // The exact hole in the 2.0.1 report: "Connected to <url>" comes from a
        // stored-token check that ignores app mode, so a user parked in Local
        // mode looks connected while no library sync ever runs.
        val body = renderDiagnostics(snapshot(appMode = AppMode.LOCAL, localLibraryId = "local_1"))
        assertTrue(body.contains("Source: Local folder"))
        assertTrue(body.contains("Sync: not running (Local folder mode)"))
    }

    @Test
    fun `local mode still reports the last shelf sync so staleness is visible`() {
        // Two books left over from an old sync look identical to two books that
        // synced this morning unless the report says when the sync happened.
        val body = renderDiagnostics(
            snapshot(appMode = AppMode.LOCAL, lastSync = "2 libraries, 431 books, 40320m ago")
        )
        assertTrue(body.contains("Last sync: 2 libraries, 431 books, 40320m ago"))
    }

    @Test
    fun `reports the active library id`() {
        assertTrue(renderDiagnostics(snapshot()).contains("Library: lib_abc"))
    }

    @Test
    fun `calls out a missing library selection instead of printing null`() {
        val body = renderDiagnostics(snapshot(serverLibraryId = null))
        assertTrue(body.contains("Library: NONE SELECTED"))
    }

    @Test
    fun `reports what is actually in the local database`() {
        val body = renderDiagnostics(snapshot(libraryCount = 2, bookCount = 431))
        assertTrue(body.contains("Stored: 2 libraries, 431 books (431 in the selected library)"))
    }

    @Test
    fun `separates books in the selected library from books in the database`() {
        // The reported case: a full database, an empty screen. One number for
        // both hides it, because the shelf the user is looking at is scoped to
        // the selected library and the total is not.
        val body = renderDiagnostics(snapshot(bookCount = 431, activeLibraryBookCount = 0))
        assertTrue(body.contains("Stored: 2 libraries, 431 books (0 in the selected library)"))
    }

    @Test
    fun `reports the last sync outcome`() {
        val body = renderDiagnostics(snapshot(lastSync = "FAILED (libraries: HTTP 500), 12m ago"))
        assertTrue(body.contains("Last sync: FAILED (libraries: HTTP 500), 12m ago"))
    }

    @Test
    fun `keeps the fields the old report already carried`() {
        val body = renderDiagnostics(snapshot())
        listOf(
            "App Version: v2.0.1 (201)",
            "Build Type: release",
            "Device: samsung SM-S938U",
            "Android: 16 (API 36)",
            "Connection: Connected to https://example.test/abs",
            "EQ Enabled: false",
            "Auto-Rewind: smart (15s)",
            "Sleep Motion: true, Shake: true",
        ).forEach { assertTrue("missing: $it", body.contains(it)) }
    }
}
