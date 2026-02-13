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
