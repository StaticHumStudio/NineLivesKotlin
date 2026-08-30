package com.ninelivesaudio.app.entitlement

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ninelivesaudio.app.MainActivity
import com.ninelivesaudio.app.R
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject

internal data class TrialReminderPlan(
    val uniqueWorkName: String,
    val existingWorkPolicy: ExistingWorkPolicy,
    val targetAtEpochMs: Long,
    val initialDelayMs: Long,
) {
    companion object {
        const val UNIQUE_WORK_NAME = "trial-three-days-remaining"
        private val REMINDER_AFTER_MS = TimeUnit.DAYS.toMillis(11)

        fun forStart(startedAtEpochMs: Long, nowEpochMs: Long): TrialReminderPlan {
            val target = if (startedAtEpochMs > Long.MAX_VALUE - REMINDER_AFTER_MS) {
                Long.MAX_VALUE
            } else {
                startedAtEpochMs + REMINDER_AFTER_MS
            }
            return TrialReminderPlan(
                uniqueWorkName = UNIQUE_WORK_NAME,
                existingWorkPolicy = ExistingWorkPolicy.REPLACE,
                targetAtEpochMs = target,
                initialDelayMs = (target - nowEpochMs).coerceAtLeast(0L),
            )
        }
    }
}

/** What the one-shot trial reminder worker should do with this run. */
internal enum class TrialReminderDecision {
    /** A fresh refresh confirms an active trial. Show the notification. */
    POST,

    /**
     * A completed answer says not to post: ownership isn't a trial, or the
     * refresh itself failed. Either way this run is done for good.
     */
    SKIP,

    /**
     * The refresh could not even run because the sequencer was busy with
     * another one. This run learned nothing, so it must not consume the
     * one-shot reminder. The worker should retry.
     */
    RETRY,
}

/** How many attempts the busy-sequencer path may burn before giving up. */
internal const val MAX_BUSY_REMINDER_RETRIES = 5

/**
 * The reminder is scheduled for three days out, but WorkManager may run it
 * late (device off, battery deferral) or the end time may be unknown. The
 * copy follows the actual milliseconds remaining at fire time, not a
 * calendar day count: [TrialPolicy] rounds a partial day up, so a trial
 * expiring at 10 a.m. still reports one day remaining at 2 a.m. that same
 * day, and that rounded count alone can't tell "ends in eight hours" apart
 * from "ends in twenty-three hours". Bucketing on real elapsed time instead
 * means no midnight, or any other calendar boundary, can make the copy lie.
 */
internal sealed interface TrialReminderCopy {
    data object Today : TrialReminderCopy
    data object OneDay : TrialReminderCopy
    data class Many(val days: Int) : TrialReminderCopy
}

private val TRIAL_REMINDER_DAY_MS: Long = TimeUnit.DAYS.toMillis(1)

internal fun trialReminderCopy(trialEndsAtEpochMs: Long?, nowEpochMs: Long): TrialReminderCopy {
    if (trialEndsAtEpochMs == null) return TrialReminderCopy.Many(3)
    val remainingMs = trialEndsAtEpochMs - nowEpochMs
    return when {
        // Zero, negative, or under a day: "ends soon" covers an already-expired
        // trial too, which can reach this selector even though it can never
        // reach POST (the refresh-and-check guard upstream rules that out).
        remainingMs < TRIAL_REMINDER_DAY_MS -> TrialReminderCopy.Today
        remainingMs < 2 * TRIAL_REMINDER_DAY_MS -> TrialReminderCopy.OneDay
        else -> TrialReminderCopy.Many(((remainingMs - 1) / TRIAL_REMINDER_DAY_MS + 1).toInt())
    }
}

internal suspend fun trialReminderDecision(
    refreshPlayOwnership: suspend () -> RefreshPurchasesResult,
    currentState: suspend () -> EntitlementState,
): TrialReminderDecision = when (refreshPlayOwnership()) {
    RefreshPurchasesResult.BUSY -> TrialReminderDecision.RETRY
    RefreshPurchasesResult.FAILED -> TrialReminderDecision.SKIP
    RefreshPurchasesResult.SUCCEEDED ->
        if (currentState().source == EntitlementSource.TRIAL) {
            TrialReminderDecision.POST
        } else {
            TrialReminderDecision.SKIP
        }
}

/**
 * Whether WorkManager should retry this attempt.
 *
 * Only [TrialReminderDecision.RETRY] is ever retryable, and even that gives up
 * past [MAX_BUSY_REMINDER_RETRIES] rather than retrying for the life of the
 * install. A genuine failure ([TrialReminderDecision.SKIP]) is a completed
 * answer and never retries, at any attempt count.
 */
internal fun shouldRetry(decision: TrialReminderDecision, runAttemptCount: Int): Boolean =
    decision == TrialReminderDecision.RETRY && runAttemptCount < MAX_BUSY_REMINDER_RETRIES

internal class WorkManagerTrialReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : TrialReminderScheduler {
    override fun schedule(startedAtEpochMs: Long) {
        val plan = TrialReminderPlan.forStart(startedAtEpochMs, System.currentTimeMillis())
        val request = OneTimeWorkRequestBuilder<TrialReminderWorker>()
            .setInitialDelay(plan.initialDelayMs, TimeUnit.MILLISECONDS)
            .build()

        try {
            WorkManager.getInstance(context).enqueueUniqueWork(
                plan.uniqueWorkName,
                plan.existingWorkPolicy,
                request,
            )
        } catch (e: Exception) {
            // The trial is valid even if WorkManager is unavailable. The single
            // reminder is explicitly best-effort.
            Log.d(TAG, "trial reminder could not be scheduled: ${e.message}")
        }
    }

    override fun cancel() {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(TrialReminderPlan.UNIQUE_WORK_NAME)
        } catch (e: Exception) {
            Log.d(TAG, "trial reminder could not be cancelled: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "TrialReminder"
    }
}

class TrialReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun billingManager(): BillingManager
        fun entitlementRepository(): EntitlementRepository
    }

    private val deps: Deps by lazy {
        EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
    }

    override suspend fun doWork(): Result {
        // A purchase can happen after WorkManager accepted this request. Query
        // Play at fire time rather than trusting the process cache captured then.
        // If Play cannot answer, skip this best-effort reminder.
        val billing = deps.billingManager()
        val entitlements = deps.entitlementRepository()
        var observedState: EntitlementState? = null
        val decision = trialReminderDecision(
            refreshPlayOwnership = { billing.refreshPurchases() },
            currentState = {
                billing.afterPendingEntitlementUpdates {
                    entitlements.current
                }.also { observedState = it }
            },
        )

        return when {
            decision == TrialReminderDecision.POST -> {
                TrialNotifications.show(
                    applicationContext,
                    trialReminderCopy(observedState?.trialEndsAtEpochMs, System.currentTimeMillis()),
                )
                Result.success()
            }
            // A busy sequencer answered nothing, so this run must not consume
            // the one-shot reminder. Retry with WorkManager's own backoff,
            // capped so a sequencer that never clears cannot retry forever.
            shouldRetry(decision, runAttemptCount) -> Result.retry()
            else -> Result.success()
        }
    }
}

private object TrialNotifications {
    private const val CHANNEL_ID = "trial_reminder"
    private const val NOTIFICATION_ID = 4713

    fun show(context: Context, copy: TrialReminderCopy) {
        val title = when (copy) {
            is TrialReminderCopy.Today -> context.getString(R.string.trial_reminder_title_today)
            is TrialReminderCopy.OneDay -> context.getString(R.string.trial_reminder_title_one)
            is TrialReminderCopy.Many -> context.getString(R.string.trial_reminder_title_many, copy.days)
        }
        val body = when (copy) {
            is TrialReminderCopy.Today -> context.getString(R.string.trial_reminder_body_today)
            is TrialReminderCopy.OneDay -> context.getString(R.string.trial_reminder_body_one)
            is TrialReminderCopy.Many -> context.getString(R.string.trial_reminder_body_many, copy.days)
        }
        try {
            ensureChannel(context)
            NotificationManagerCompat.from(context).notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setContentIntent(openApp(context))
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build(),
            )
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS denied. This reminder is best-effort.
        } catch (e: Exception) {
            Log.d("TrialReminder", "trial reminder could not be posted: ${e.message}")
        }
    }

    private fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.trial_reminder_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.trial_reminder_channel_description)
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        NOTIFICATION_ID,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
