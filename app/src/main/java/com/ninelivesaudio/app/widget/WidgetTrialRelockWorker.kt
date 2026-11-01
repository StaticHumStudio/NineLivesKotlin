package com.ninelivesaudio.app.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ninelivesaudio.app.entitlement.EntitlementRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Runs just after a trial ends. Re-resolves entitlement (the resolver sees
 * the trial is over) and redraws the widgets, so they relock even when the
 * app was closed at the moment the trial ran out.
 */
class WidgetTrialRelockWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun entitlementRepository(): EntitlementRepository
        fun widgetRefresher(): WidgetRefresher
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        deps.entitlementRepository().refresh()
        deps.widgetRefresher().pushNow(setOf(WidgetTarget.BINDER, WidgetTarget.HOME))
        return Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "widget_trial_relock"
    }
}
