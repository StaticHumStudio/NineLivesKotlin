package com.ninelivesaudio.app.entitlement

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plain prefs holding everything about entitlement that must NOT survive a
 * device transfer. Excluded from backup in both `backup_rules.xml` and
 * `data_extraction_rules.xml`, in all three sections.
 *
 * This is the opposite direction from [EntitlementPrefs], and the split is the
 * whole point. A Play purchase belongs to a Google account, not a device, so
 * `queryPurchasesAsync` restores it legitimately on new hardware. The cache
 * exists only so a device that already bought keeps working while Billing is
 * down. Let the cache ride Auto Backup and it becomes a portable unlock,
 * because a restored grant on an account that never paid can never meet the
 * successful-empty-query condition that would revoke it while Billing stays
 * unreachable.
 *
 * Verify the include/exclude for BOTH files in BOTH rule documents whenever
 * either one changes. Do not assume the default.
 */
@Singleton
class EntitlementCachePrefs @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /**
     * Last known PURCHASED state of `nine_lives_unlock`.
     *
     * Only ever written from a successful Billing result. A query error, a
     * timeout, or a disconnected Billing service must leave this untouched
     * rather than writing false, or every offline launch would revoke.
     */
    var playUnlockCached: Boolean
        get() = prefs.getBoolean(KEY_PLAY_UNLOCK, false)
        set(value) = prefs.edit().putBoolean(KEY_PLAY_UNLOCK, value).apply()

    /**
     * Release-build test override. Suppresses the `legacy_paid` source only.
     *
     * The only test device carries the grandfather flag, so without this there
     * is no way to see the free tier on real hardware during the internal-track
     * pass. Lives here rather than in [EntitlementPrefs] precisely so it cannot
     * ride a backup onto another device.
     */
    var forceFree: Boolean
        get() = prefs.getBoolean(KEY_FORCE_FREE, false)
        set(value) = prefs.edit().putBoolean(KEY_FORCE_FREE, value).apply()

    /**
     * The book a free install keeps offline, resolved once at the entitlement
     * drop rather than recomputed on every read.
     *
     * A rule recomputed on every read is not a slot: the winner would silently
     * change as playback timestamps moved, and the book the user actually has on
     * disk would stop being the one the app thinks it kept.
     *
     * Lives in the non-backed-up file on purpose. A slot winner is a fact about
     * files on THIS device, so restoring it onto new hardware would name a book
     * that is not there.
     */
    var slotWinnerAudioBookId: String?
        get() = prefs.getString(KEY_SLOT_WINNER, null)
        set(value) = prefs.edit().putString(KEY_SLOT_WINNER, value).apply()

    companion object {
        const val FILE_NAME = "nine_lives_entitlement_cache"
        const val KEY_SLOT_WINNER = "slot_winner_audiobook_id"
        const val KEY_PLAY_UNLOCK = "play_unlock_cached"
        const val KEY_FORCE_FREE = "force_free"
    }
}
