package com.ninelivesaudio.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    val appMode: AppMode = AppMode.LOCAL,
    val onboardingComplete: Boolean = false,
    val serverUrl: String = "",
    val username: String = "",
    val useApiToken: Boolean = false, // true = API token login, false = username/password
    val selectedLibraryId: String? = null, // persisted library selection
    val selectedLocalLibraryId: String? = null, // persisted local library selection
    val downloadPath: String = "",
    val autoDownloadCovers: Boolean = true,
    val playbackSpeed: Double = 1.0,
    val autoSyncProgress: Boolean = true,
    val syncIntervalMinutes: Int = 5,
    val volume: Double = 0.8,
    val eqEnabled: Boolean = false,
    // 5-band EQ gains in millibels (-1500 to +1500), indexed by band 0–4.
    // Default frequencies (Hz): 60, 230, 910, 3.6k, 14k
    // (actual count depends on device; resized at runtime)
    val eqBandGains: List<Int> = List(5) { 0 },
    val volumeBoostGain: Int = 0, // millibels, 0–1000 (0–10 dB)
    // Media3 built-in silence trimming. Unlock feature, normalized off for free
    // by EffectiveSettings rather than being hidden.
    val skipSilenceEnabled: Boolean = false,
    val allowSelfSignedCertificates: Boolean = false,
    val diagnosticsMode: Boolean = false,
    val serverProfiles: List<ServerProfile> = emptyList(),
    // Archive Beneath is permanent. There is no "normal" mode.
    val unhingedThemeEnabled: Boolean = true,
    // Selectable color theme. Defaults to NOIR so existing users see no change.
    val themeMode: ThemeMode = ThemeMode.NOIR,
    val anomaliesEnabled: Boolean = true,
    val whispersEnabled: Boolean = true,
    val copyMode: String = "Unhinged", // Normal, Ritual, or Unhinged
    // Auto-Rewind on Resume
    val autoRewindEnabled: Boolean = true,
    val autoRewindMode: String = "smart",    // "smart" or "flat"
    val autoRewindSeconds: Int = 15,         // flat mode: 0–120, step 5
    // Sleep Timer enhancements
    val sleepTimerMotionEnabled: Boolean = true,
    val sleepTimerShakeResetEnabled: Boolean = true,
    val sleepTimerRewindSeconds: Int = 15,   // rewind on timer stop: 0–60, step 5
    // Dossier: count archived (LOCAL soft-deleted) books in listening stats.
    val includeArchivedInStats: Boolean = true,
    val lastSeenChangelogVersion: String = "",
    val lastSync: LastSyncRecord? = null,
    // Per-install high water mark for persisted sync outcomes. Ordering a
    // verdict by wall time lets a clock rollback reject every later sync.
    val lastSyncOutcomeSequence: Long = 0L,
    // Where each library's book list stood after its last good sync, so a
    // cold start can ask the server "anything new?" instead of downloading
    // every book again. Kept here rather than in Room: no schema migration,
    // it rides the same durable document as lastSync, and it is written only
    // after the books it describes are already saved, so a crash can only
    // leave it older than the shelf, never ahead of it.
    val librarySyncWatermarks: List<LibrarySyncWatermark> = emptyList(),
) {
    /** The selected library for the current source mode. The two modes never share a fallback. */
    val activeLibraryId: String?
        get() = when (appMode) {
            AppMode.LOCAL -> selectedLocalLibraryId
            AppMode.AUDIOBOOKSHELF -> selectedLibraryId
        }
}

@Serializable
enum class SyncResult {
    SUCCESS,
    PARTIAL,
    FAILED,
}

@Serializable
data class LastSyncRecord(
    val result: SyncResult,
    val libraryCount: Int,
    val bookCount: Int,
    val failure: String? = null,
    val completedAtMs: Long,
    // A persisted outcome order, independent from the wall clock used for
    // support-facing age text. Defaults preserve records from before this
    // ordering field existed.
    val outcomeSequence: Long = 0L,
    // The item-library IDs whose fetches did not complete cleanly in this
    // sync run. Null preserves the conservative behavior for records stored
    // before this scope existed, or outcomes that failed before item scope
    // was knowable. An empty list means every attempted item fetch was clean.
    val failedLibraryIds: List<String>? = null,
    // The server this record was produced against (AppSettings.serverUrl at
    // persist time). A record is only a verdict about the shelf for the
    // server that is CURRENTLY configured. A leftover record from a server
    // the user has since switched away from must never render as that other
    // server's sync state. Defaults to "" so a record deserialized from
    // before this field existed is treated as belonging to no known server
    // (never matches a real serverUrl) rather than crashing on missing JSON.
    val serverUrl: String = "",
)

/**
 * One library's book list as of its last complete sync, for one server and
 * account. [newestAddedAt] and [newestItemId] are the most recently added
 * book the server had then (null for an empty library), [itemCount] is how
 * many books it reported, and [lastFullSyncAtMs] is the wall clock time of
 * the last full download, which bounds how long a metadata edit can go
 * unseen (the server cannot be asked for edited books, only added ones).
 * [overlapItemIds] lists the books [itemCount] includes whose added date is
 * within a minute of [newestAddedAt], so the next check can tell a book
 * stamped just before the newest one apart from one it already counted.
 * Null on a watermark saved before the list existed.
 */
@Serializable
data class LibrarySyncWatermark(
    val serverUrl: String,
    val username: String,
    val libraryId: String,
    val newestAddedAt: Long? = null,
    val newestItemId: String? = null,
    val itemCount: Int = 0,
    val overlapItemIds: List<String>? = null,
    val lastFullSyncAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
)

@Serializable
data class ServerProfile(
    val name: String = "",
    val url: String = "",
    val username: String = "",
    val isDefault: Boolean = false,
    val lastConnected: Long? = null, // epoch millis
)
