package com.ninelivesaudio.app.entitlement

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plain (non-encrypted) prefs holding entitlement facts that must survive the
 * install lineage through Auto Backup.
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
 * ## The legacy flag is read-only, permanently
 *
 * There is no writer for `legacy_paid` and there must never be one again. The
 * unconditional first-launch writer shipped in 2.0.2 and was deleted here.
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
 * ## The paid population is zero, and that is now literally true

 * CORRECTED 2026-08-21. A stranger bought the paid app on 2026-08-16, which
 * briefly made the population two rather than one. Jeff refunded them on
 * 2026-08-21, so the transaction is unwound and they stand exactly where any
 * free user stands.
 *
 * Two mechanisms were built for that one person and both were thrown away. A
 * date-gated writer went first: it worked, but stayed safe only while a human
 * remembered to flip the price after a compiled-in cutoff, and one forgotten
 * ordering rule would have grandfathered every free install. A one-time claim
 * prompt went second: it could not tell who had paid, so it guessed from
 * install date and told every pre-cutoff install "You paid for this", the Play
 * reviewer included.
 *
 * What survives is the direct contact row in Settings. Anyone who believes they
 * bought this writes in and gets answered by hand with a promo code for
 * `nine_lives_unlock`. That scales to the population it has to serve, which is
 * zero, and it cannot lie to anybody because a human reads it first.
 *
 * If real paid volume ever appears in the order history before the flip,
 * revisit this. Hand-recovery does not scale, and the writer is only safe under
 * a rule nobody will remember.
 */
@Singleton
class EntitlementPrefs @Inject constructor(
    @ApplicationContext context: Context,
) : DurableEntitlementStore {
    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override val legacyPaid: Boolean
        get() = prefs.getBoolean(KEY_LEGACY_PAID, false)

    override val trialStartedAtEpochMs: Long?
        get() = if (prefs.contains(KEY_TRIAL_STARTED_AT)) {
            prefs.getLong(KEY_TRIAL_STARTED_AT, 0L)
        } else {
            null
        }

    override val trialConsumed: Boolean
        get() = prefs.getBoolean(KEY_TRIAL_CONSUMED, false)

    /** Atomically latch the one-time trial before the repository grants it. */
    override fun consumeTrial(startedAtEpochMs: Long): Boolean {
        if (trialConsumed || trialStartedAtEpochMs != null) return false
        return prefs.edit()
            .putLong(KEY_TRIAL_STARTED_AT, startedAtEpochMs)
            .putBoolean(KEY_TRIAL_CONSUMED, true)
            // The watermark starts at the trial's own clock, not at whatever
            // this device's clock happened to read before the trial existed.
            // A stray future reading from before the tap must never poison a
            // trial that has not started yet.
            .putLong(KEY_TRIAL_LATEST_SEEN, startedAtEpochMs)
            // Restoring a pre-trial backup, or reinstalling without a restore,
            // can re-arm this local trial. Enforcing lifetime-once would require
            // phoning home. The studio accepts that loss at this price point.
            // Synchronous on purpose. A process death after an asynchronous
            // grant but before disk flush could otherwise make the offer recur.
            .commit()
    }

    /**
     * Advance the trial clock high-water mark to at least [nowEpochMs].
     *
     * Only writes when the mark actually moves forward, so an attempted
     * rollback (or ordinary re-resolution at an unchanged time) costs no disk
     * write. Synchronous like [consumeTrial]: a lost async write here would
     * reopen exactly the rollback window this mark exists to close.
     */
    override fun advanceTrialWatermark(nowEpochMs: Long): Long? {
        val startedAt = trialStartedAtEpochMs ?: return null
        val current = if (prefs.contains(KEY_TRIAL_LATEST_SEEN)) {
            prefs.getLong(KEY_TRIAL_LATEST_SEEN, startedAt)
        } else {
            // A trial started before this field existed. Fall back to its own
            // start rather than to now, for the same reason consumeTrial seeds
            // the mark at start: now could itself be a stray future reading.
            startedAt
        }
        val advanced = maxOf(current, nowEpochMs)
        if (advanced != current) {
            prefs.edit().putLong(KEY_TRIAL_LATEST_SEEN, advanced).commit()
        }
        return advanced
    }

    companion object {
        const val FILE_NAME = "nine_lives_entitlement"
        const val KEY_LEGACY_PAID = "legacy_paid"
        const val KEY_TRIAL_STARTED_AT = "trial_started_at_epoch_ms"
        const val KEY_TRIAL_CONSUMED = "trial_consumed"
        const val KEY_TRIAL_LATEST_SEEN = "trial_latest_seen_epoch_ms"
    }
}
