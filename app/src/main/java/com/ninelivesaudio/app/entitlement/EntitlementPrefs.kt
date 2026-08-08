package com.ninelivesaudio.app.entitlement

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plain (non-encrypted) prefs holding the grandfather signal only.
 *
 * This file MUST stay out of `nine_lives_secure_prefs`: the secure prefs are
 * excluded from backup in both backup_rules.xml and data_extraction_rules.xml,
 * and `legacy_paid` exists precisely to survive uninstall/reinstall and
 * device-to-device transfer via Auto Backup. A boolean "this install predates
 * the free switch" is not sensitive.
 *
 * The Play-grant cache (added later) goes in a SEPARATE file,
 * `nine_lives_entitlement_cache`, excluded from backup. Never merge the two:
 * a backed-up Play grant becomes a portable unlock on accounts that never paid.
 */
@Singleton
class EntitlementPrefs @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    val isLegacyPaid: Boolean
        get() = prefs.getBoolean(KEY_LEGACY_PAID, false)

    fun markLegacyPaid() {
        prefs.edit().putBoolean(KEY_LEGACY_PAID, true).apply()
    }

    companion object {
        const val FILE_NAME = "nine_lives_entitlement"
        const val KEY_LEGACY_PAID = "legacy_paid"
    }
}
