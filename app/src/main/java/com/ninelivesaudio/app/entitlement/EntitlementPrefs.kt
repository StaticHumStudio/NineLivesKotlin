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
 * predates the switch. Everyone else is recovered by hand.
 *
 * ## The paid population is no longer just Jeff
 *
 * CORRECTED 2026-08-20. This used to end by saying manual recovery was
 * affordable "precisely because the paid population is one person". That stopped
 * being true on 2026-08-16, when a stranger bought the paid app. Since nothing shipped ever writes this
 * flag, they carry no grandfather signal and land on the free tier when 2.1.0
 * reaches production. Their order identifier is deliberately not recorded in
 * this repo, which is public.
 *
 * A date-gated writer was built to catch them and then deliberately thrown
 * away. It worked, but it only stayed safe while a human remembered to flip the
 * price AFTER a compiled-in cutoff, and one forgotten ordering rule would have
 * grandfathered every free install and quietly ended the paid tier. Jeff's call:
 * refund the buyer instead, leave them the free app, and carry the note in
 * Settings offering a free unlock code to anyone who bought before the switch.
 * Money back beats clever code.
 *
 * So manual recovery is still the plan, and it is still affordable, just for a
 * different reason: the recovery path is a support email answered with a promo
 * code, and the population it has to serve is tiny rather than theoretically
 * zero. If real paid volume ever shows up in the order history before the flip,
 * revisit this, because hand-recovery does not scale and the writer is only safe
 * under a rule nobody will remember.
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
