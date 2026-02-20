package com.ninelivesaudio.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    val serverUrl: String = "",
    val username: String = "",
    val downloadPath: String = "",
    val autoDownloadCovers: Boolean = true,
    val playbackSpeed: Double = 1.0,
    val autoSyncProgress: Boolean = true,
    val syncIntervalMinutes: Int = 5,
    val volume: Double = 0.8,
    val eqEnabled: Boolean = false,
    // 9-band EQ gains in millibels (-1500 to +1500), indexed by band 0–8.
    // Default frequencies (Hz): 31, 62, 125, 250, 500, 1k, 2k, 4k, 8k, 16k
    // (actual count depends on device; we persist 9 bands)
    val eqBandGains: List<Int> = List(9) { 0 },
    val allowSelfSignedCertificates: Boolean = false,
    val diagnosticsMode: Boolean = false,
    val serverProfiles: List<ServerProfile> = emptyList(),
    // Archive Beneath is permanent. There is no "normal" mode.
    val unhingedThemeEnabled: Boolean = true,
    val anomaliesEnabled: Boolean = true,
    val whispersEnabled: Boolean = true,
    val copyMode: String = "Unhinged", // Normal, Ritual, or Unhinged
)

@Serializable
data class ServerProfile(
    val name: String = "",
    val url: String = "",
    val username: String = "",
    val isDefault: Boolean = false,
    val lastConnected: Long? = null, // epoch millis
)
