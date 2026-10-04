package com.ninelivesaudio.app.data.repository

import com.ninelivesaudio.app.domain.model.LibrarySyncWatermark
import com.ninelivesaudio.app.service.SettingsManager
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where each library's last good sync stands, per server and account,
 * persisted in the settings document next to the last sync record.
 *
 * Written only after the books it describes are saved, and only while the
 * same server and account are still signed in, so a write can never land on
 * someone else's session. A failed write is logged by the caller's absence of
 * a watermark next time, which only costs a full download.
 */
@Singleton
class LibrarySyncWatermarkStore @Inject constructor(
    private val settingsManager: SettingsManager,
) {
    internal fun currentAccount(): SyncAccountKey {
        val settings = settingsManager.currentSettings
        return SyncAccountKey(serverUrl = settings.serverUrl, username = settings.username)
    }

    internal fun get(key: SyncAccountKey, libraryId: String): LibrarySyncWatermark? =
        settingsManager.currentSettings.librarySyncWatermarks.watermarkFor(key, libraryId)

    /** Saves [watermark] if its server and account are still the signed-in ones. */
    internal suspend fun put(watermark: LibrarySyncWatermark): Boolean = write { settings ->
        if (settings.serverUrl != watermark.serverUrl || settings.username != watermark.username) {
            settings
        } else {
            settings.copy(librarySyncWatermarks = settings.librarySyncWatermarks.withWatermark(watermark))
        }
    }

    /** Forgets a library's watermark so its next check runs a full download. */
    internal suspend fun remove(key: SyncAccountKey, libraryId: String): Boolean = write { settings ->
        val kept = settings.librarySyncWatermarks.withoutWatermark(key, libraryId)
        if (kept.size == settings.librarySyncWatermarks.size) settings else settings.copy(librarySyncWatermarks = kept)
    }

    private suspend fun write(
        transform: (com.ninelivesaudio.app.domain.model.AppSettings) -> com.ninelivesaudio.app.domain.model.AppSettings,
    ): Boolean = try {
        settingsManager.updateSettingsIfAuthenticated(transform)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }
}
