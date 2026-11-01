package com.ninelivesaudio.app.widget.binder

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import com.ninelivesaudio.app.entitlement.EntitlementRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The Binder: a 2x1 home screen widget that flips through the library's
 * covers like an old CD binder. Updates come from the app's own events
 * through `WidgetRefresher`, and the hourly system update is a backstop
 * that relocks a widget after a trial ends with the app closed.
 */
class BinderWidgetProvider : AppWidgetProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun binderUpdater(): BinderWidgetUpdater
        fun configStore(): BinderConfigStore
        fun entitlementRepository(): EntitlementRepository
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val deps = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Re-resolve entitlement first, so an expired trial relocks here.
                deps.entitlementRepository().refresh()
                deps.binderUpdater().update(appWidgetIds)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)
            .configStore().remove(appWidgetIds)
    }
}
