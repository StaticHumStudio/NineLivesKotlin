package com.ninelivesaudio.app.ui.settings

import com.ninelivesaudio.app.domain.model.AppMode

internal data class DiagnosticsSnapshot(
    val appVersion: String,
    val buildType: String,
    val device: String,
    val androidVersion: String,
    val connection: String,
    val appMode: AppMode,
    val serverLibraryId: String?,
    val localLibraryId: String?,
    val libraryCount: Int,
    val bookCount: Int,
    val activeLibraryBookCount: Int,
    val lastSync: String,
    val eqEnabled: Boolean,
    val autoRewind: String,
    val sleepMotion: Boolean,
    val sleepShake: Boolean,
)

/**
 * The bug report body. Everything here exists to answer "why is my shelf
 * empty" without a code trace: which source the app is actually reading, which
 * library is selected, what is in the database right now, and what the last
 * sync produced.
 */
internal fun renderDiagnostics(snapshot: DiagnosticsSnapshot): String = buildString {
    appendLine("App Version: ${snapshot.appVersion}")
    appendLine("Build Type: ${snapshot.buildType}")
    appendLine("Device: ${snapshot.device}")
    appendLine("Android: ${snapshot.androidVersion}")
    appendLine("Connection: ${snapshot.connection}")
    appendLine("Source: ${sourceLabel(snapshot.appMode)}")
    appendLine("Library: ${activeLibraryLabel(snapshot)}")
    // Two numbers, because the shelf on screen is scoped to the selected
    // library and the database total is not. A full database behind an empty
    // screen is the whole diagnosis in one line.
    appendLine(
        "Stored: ${snapshot.libraryCount} libraries, ${snapshot.bookCount} books " +
            "(${snapshot.activeLibraryBookCount} in the selected library)"
    )
    // "Connection: Connected to ..." comes from a stored-token check that does
    // not look at app mode, so it stays true in Local folder mode while no
    // library sync ever runs. Say that outright rather than leaving the two
    // lines to contradict each other.
    if (snapshot.appMode == AppMode.LOCAL) {
        appendLine("Sync: not running (Local folder mode)")
    }
    appendLine("Last sync: ${snapshot.lastSync}")
    appendLine("EQ Enabled: ${snapshot.eqEnabled}")
    appendLine("Auto-Rewind: ${snapshot.autoRewind}")
    appendLine("Sleep Motion: ${snapshot.sleepMotion}, Shake: ${snapshot.sleepShake}")
}

private fun sourceLabel(mode: AppMode): String = when (mode) {
    AppMode.LOCAL -> "Local folder"
    AppMode.AUDIOBOOKSHELF -> "Audiobookshelf"
}

private fun activeLibraryLabel(snapshot: DiagnosticsSnapshot): String {
    val id = when (snapshot.appMode) {
        AppMode.LOCAL -> snapshot.localLibraryId
        AppMode.AUDIOBOOKSHELF -> snapshot.serverLibraryId
    }
    return id ?: "NONE SELECTED"
}
