package com.ninelivesaudio.app.data.remote

import com.ninelivesaudio.app.service.SettingsManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Audiobookshelf user the phone's unsent progress belongs to (#118).
 * Queued spots, offline listening and the record of what the server has
 * held carry the signed-in user's id, a UUID unique to that user on that
 * server. So the same server reached by another address (LAN IP, domain,
 * Tailscale) is still the same owner, and a switch to another server or
 * another account never sends one user's listening to another, drops it
 * there as "not found", or resets books that user never had.
 */
@Singleton
class ProgressAccountScope(
    private val storedUserId: () -> String?,
    private val fetchUserId: suspend () -> String?,
) {
    @Inject constructor(settingsManager: SettingsManager, apiService: ApiService) : this(
        storedUserId = settingsManager::getAuthUserId,
        fetchUserId = apiService::fetchSignedInUserId,
    )

    /** The signed-in user as the phone knows it, or "" when not known yet. No network. */
    fun known(): String = storedUserId()?.trim().orEmpty()

    /**
     * The signed-in user, asked of the server when the phone does not know
     * it yet. "" when it cannot be found, and then nothing may be delivered,
     * dropped or reset for anyone.
     */
    suspend fun resolve(): String = known().ifEmpty { fetchUserId()?.trim().orEmpty() }
}
