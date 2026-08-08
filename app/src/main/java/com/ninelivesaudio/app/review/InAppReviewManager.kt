package com.ninelivesaudio.app.review

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.google.android.play.core.review.ReviewManagerFactory
import com.google.android.play.core.ktx.launchReview
import com.google.android.play.core.ktx.requestReview
import com.ninelivesaudio.app.data.local.dao.PlaybackProgressDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Asks Play for a review prompt, at a moment that has earned it.
 *
 * The policy lives in [ReviewEligibility] and is unit-tested. This is the part
 * that needs a device: install age, listening history, and the Play call itself.
 *
 * ## What this deliberately does not do
 *
 * It is never called from a button. Play's quota is silent and it may show
 * nothing at all, so a control that sometimes does nothing reads as broken. The
 * manual path is a Settings row that deep-links to the listing, which always
 * does something visible.
 *
 * It never asks a screening question first. Routing only happy users to the
 * prompt is a policy violation and produces a review corpus that is worthless
 * the moment anyone reads it.
 *
 * It is not gated by entitlement. Free users are exactly the ones whose ratings
 * the funnel needs.
 */
@Singleton
class InAppReviewManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playbackProgressDao: PlaybackProgressDao,
    private val prefs: ReviewPrefs,
) {
    /**
     * Consider prompting. Call ONLY from a success moment: a finished book, or a
     * session that crossed a real listening threshold. Never from a payment, a
     * permission result, or any failure state.
     *
     * Silent by design. Nothing here surfaces an error, because there is no
     * outcome the user should be told about.
     */
    suspend fun maybeRequestReview(activity: Activity) = attemptMutex.withLock {
        // Serialized. Check-then-stamp across a suspension point otherwise lets
        // two success moments arriving together both pass eligibility and both
        // launch, which shows the user two prompts and burns the quota twice.
        if (!isEligible()) return@withLock

        try {
            val manager = ReviewManagerFactory.create(context)
            val info = manager.requestReview()

            // Stamped AFTER Play has actually handed back a flow, and BEFORE
            // launching it. Both halves matter. Stamping earlier means a
            // transient request failure suppresses every legitimate retry for
            // the whole cooldown without Play ever hearing about it. Stamping
            // later means a shown-but-declined prompt is never recorded, because
            // Play reports nothing about what the user did.
            prefs.lastAttemptAt = System.currentTimeMillis()

            manager.launchReview(activity, info)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Includes the entirely normal case of Play declining. Not an error
            // worth surfacing, and not one worth retrying either.
            Log.d(TAG, "review flow unavailable: ${e.message}")
        }
    }

    /** Records that this process reported a crash, so it stops asking today. */
    fun markCrashedThisSession() {
        crashedThisSession = true
    }

    private suspend fun isEligible(): Boolean =
        ReviewEligibility.isEligible(
            ReviewSignals(
                installAgeDays = installAgeDays(),
                completedBooks = playbackProgressDao.countFinished(),
                listeningSessions = playbackProgressDao.countStarted(),
                crashedThisSession = crashedThisSession,
                lastAttemptAt = prefs.lastAttemptAt,
                now = System.currentTimeMillis(),
            )
        )

    private fun installAgeDays(): Long {
        // PackageInfoFlags is API 33+, and minSdk here is 30. The deprecated
        // overload is the only one that exists on 30 through 32, and without the
        // split the runCatching below would swallow a NoSuchMethodError into an
        // install age of zero, silently disabling the prompt forever on those
        // versions. A failure that looks like a policy decision is the worst
        // kind.
        val firstInstall = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager
                    .getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
                    .firstInstallTime
            } else {
                @Suppress("DEPRECATION")
                context.packageManager
                    .getPackageInfo(context.packageName, 0)
                    .firstInstallTime
            }
        }.getOrNull() ?: return 0

        return TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - firstInstall)
    }

    private val attemptMutex = Mutex()

    private companion object {
        const val TAG = "InAppReviewManager"
    }

    /**
     * Per-process, not persisted. "Did it crash TODAY" is exactly what this
     * should mean, and a fresh process is a fresh answer.
     */
    @Volatile
    private var crashedThisSession = false
}
