package com.ninelivesaudio.app.entitlement

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.ninelivesaudio.app.MainActivity
import com.ninelivesaudio.app.R
import dagger.hilt.android.qualifiers.ApplicationContext
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

internal fun shouldPostTrialReminder(state: EntitlementState): Boolean =
    state.source == EntitlementSource.TRIAL

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

    private companion object {
        const val TAG = "TrialReminder"
    }
}

class TrialReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {
    override fun doWork(): Result {
        val prefs = EntitlementPrefs(applicationContext)
        val cache = EntitlementCachePrefs(applicationContext)
        val state = EntitlementResolver.resolve(
            legacyPaid = prefs.legacyPaid,
            playUnlocked = cache.playUnlockCached,
            forceFree = cache.forceFree,
            trialStartedAtEpochMs = prefs.trialStartedAtEpochMs,
            trialConsumed = prefs.trialConsumed,
            nowEpochMs = System.currentTimeMillis(),
        )

        if (shouldPostTrialReminder(state)) {
            TrialNotifications.show(applicationContext)
        }
        return Result.success()
    }
}

private object TrialNotifications {
    private const val CHANNEL_ID = "trial_reminder"
    private const val NOTIFICATION_ID = 4713

    fun show(context: Context) {
        try {
            ensureChannel(context)
            NotificationManagerCompat.from(context).notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(context.getString(R.string.trial_reminder_title))
                    .setContentText(context.getString(R.string.trial_reminder_body))
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
