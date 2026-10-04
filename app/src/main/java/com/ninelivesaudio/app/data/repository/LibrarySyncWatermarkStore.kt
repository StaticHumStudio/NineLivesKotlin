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

    /**
     * The server, account, and sign-in a sync runs for. Read again before
     * each write, so a sync that outlives its sign-in stops writing.
     */
    internal suspend fun currentSyncIdentity(): LibrarySyncIdentity {
        val token = try {
            settingsManager.getAuthToken()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return LibrarySyncIdentity(currentAccount(), token?.hashCode() ?: 0)
    }

    /**
     * Runs [write] only if [identity] is still the signed-in one, and holds
     * the auth token lock while it runs. Checking first and writing after
     * left a gap: the sync could pass the check, suspend inside a DAO call,
     * and resume after the next account signed in and synced, then prune
     * that account's books. Under the lock a sign-in, sign-out, or token
     * swap waits for the write, and a write that comes after one sees it.
     * Returns whether [write] ran. [write] must not touch the settings
     * document (put and remove take the same lock).
     */
    internal suspend fun runIfCurrent(identity: LibrarySyncIdentity, write: suspend () -> Unit): Boolean =
        settingsManager.withAuthTokenLocked { token ->
            if (LibrarySyncIdentity(currentAccount(), token?.hashCode() ?: 0) != identity) {
                false
            } else {
                write()
                true
            }
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
