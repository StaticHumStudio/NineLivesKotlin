package com.ninelivesaudio.app.entitlement

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers that the paid-era claim prompt has been shown, so it shows once.
 *
 * ## Deliberately its own file
 *
 * NOT merged into [EntitlementPrefs], which holds the grandfather flag and is
 * the most dangerous boolean in the app, and NOT merged into
 * [EntitlementCachePrefs], which holds the Play grant and is excluded from
 * backup precisely so a restored grant cannot become a portable unlock.
 *
 * This flag grants nothing. Keeping it separate means a mistake here can never
 * touch either of those, and it keeps both of those files' rules short enough
 * that people actually read them.
 *
 * ## Backed up on purpose
 *
 * No backup-rule change accompanies this file, which means Auto Backup picks it
 * up by default and that is the behavior we want: somebody who already
 * dismissed the prompt should not meet it again on a new phone. Note that the
 * backup rule files must NOT gain an `<include>` for it, because a single
 * include flips the whole section to allowlist mode and silently drops
 * everything else. Both rule files carry that warning.
 */
@Singleton
class PaidEraClaimPrefs @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    val wasPrompted: Boolean
        get() = prefs.getBoolean(KEY_PROMPTED, false)

    /**
     * Latch the prompt as seen.
     *
     * `commit()` rather than `apply()`: this is written as the dialog closes,
     * which is exactly when the user may be leaving the app, and an async write
     * lost to a process death shows them the prompt a second time.
     */
    fun markPrompted() {
        prefs.edit().putBoolean(KEY_PROMPTED, true).commit()
    }

    companion object {
        const val FILE_NAME = "nine_lives_paid_era_claim"
        const val KEY_PROMPTED = "claim_prompted"
    }
}
