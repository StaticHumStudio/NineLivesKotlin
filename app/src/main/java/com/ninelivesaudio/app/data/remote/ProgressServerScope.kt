package com.ninelivesaudio.app.data.remote

import com.ninelivesaudio.app.service.SettingsManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The server the phone's unsent progress belongs to (#118 final review).
 * Queued spots, offline listening and the record of what a server has held
 * carry it, so a server switch never sends one server's listening to
 * another, drops it there as "not found", or resets books another server
 * simply does not have.
 */
@Singleton
class ProgressServerScope(
    private val currentServerUrl: () -> String,
) {
    @Inject constructor(settingsManager: SettingsManager) : this({ settingsManager.currentSettings.serverUrl })

    /** The server in use now, or "" when none is set up. */
    fun current(): String = progressServerKey(currentServerUrl())
}

/**
 * One server's key: its base URL with the scheme and host lowercased, the
 * port spelled out and no trailing slash, so one server typed two ways is
 * one key. "" for no valid server.
 */
internal fun progressServerKey(serverUrl: String): String {
    val url = validatedServerBaseUrl(serverUrl) ?: return ""
    val path = url.encodedPath.trimEnd('/')
    return "${url.scheme}://${url.host}:${url.port}$path"
}
