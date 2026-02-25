package com.ninelivesaudio.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    val serverUrl: String = "",
    val username: String = "",
    val useApiToken: Boolean = false, // true = API token login, false = username/password
    val selectedLibraryId: String? = null, // persisted library selection
    val downloadPath: String = "",
    val autoDownloadCovers: Boolean = true,
    val playbackSpeed: Double = 1.0,
    val autoSyncProgress: Boolean = true,
    val syncIntervalMinutes: Int = 5,
    val volume: Double = 0.8,
    val eqEnabled: Boolean = false,
    // 9-band EQ gains in millibels (-1500 to +1500), indexed by band 0–8.
    // Default frequencies (Hz): 31, 62, 125, 250, 500, 1k, 2k, 4k, 8k
    // (actual count depends on device; we persist 9 bands)
    val eqBandGains: List<Int> = List(9) { 0 },
    val volumeBoostGain: Int = 0, // millibels, 0–1000 (0–10 dB)
    val allowSelfSignedCertificates: Boolean = false,
    val diagnosticsMode: Boolean = false,
    val serverProfiles: List<ServerProfile> = emptyList(),
    // Archive Beneath is permanent. There is no "normal" mode.
    val unhingedThemeEnabled: Boolean = true,
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
)

@Serializable
data class ServerProfile(
    val name: String = "",
    val url: String = "",
    val username: String = "",
    val isDefault: Boolean = false,
    val lastConnected: Long? = null, // epoch millis
)
