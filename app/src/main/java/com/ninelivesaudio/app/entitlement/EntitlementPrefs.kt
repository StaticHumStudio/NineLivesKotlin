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
 * The Play-grant cache lives in a SEPARATE file, [EntitlementCachePrefs],
 * excluded from backup. Never merge the two: a backed-up Play grant becomes a
 * portable unlock on accounts that never paid.
 *
 * ## Read-only, permanently
 *
 * There is no writer here and there must never be one again. The unconditional
 * first-launch writer shipped in 2.0.2 and was deleted in this build.
 *
 * That deletion is not housekeeping. `PAID_ERA_CUTOFF` was removed on
 * 2026-08-08, which makes this flag the ONLY grandfather signal. A writer left
 * anywhere in the free build means every post-flip install marks itself
 * paid-for-life on first launch and the paid tier silently ceases to exist. It
 * would not even fail loudly, because the force-free test override hides the
 * symptom from the one pass most likely to catch it.
 *
 * The flag can now only arrive from an Auto Backup restore of an install that
 * predates the switch. Anyone stranded without it is recovered with a promo
 * code for `nine_lives_unlock`, which is affordable precisely because the paid
 * population is one person.
 */
@Singleton
class EntitlementPrefs @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    val isLegacyPaid: Boolean
        get() = prefs.getBoolean(KEY_LEGACY_PAID, false)

    companion object {
        const val FILE_NAME = "nine_lives_entitlement"
        const val KEY_LEGACY_PAID = "legacy_paid"
    }
}
