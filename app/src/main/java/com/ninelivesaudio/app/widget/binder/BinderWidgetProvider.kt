package com.ninelivesaudio.app.widget.binder

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import com.ninelivesaudio.app.entitlement.EntitlementRepository
import dagger.hilt.EntryPoint
import java.util.concurrent.ConcurrentHashMap
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Binder: a resizable home screen widget that flips through the library's
 * books like an old CD binder, discs in sleeves either side of a stitched
 * spine. How many pockets a page holds follows the widget's size
 * ([binderLayoutFor]). Updates come from the app's own events
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

    /** A resize: redraw at the new size, and cut the pages again for it. */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        val deps = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)
        // A drag across sizes sends one of these per size it passes. Only the
        // last one, once the drag has settled, redraws.
        val mine = resizes.merge(appWidgetId, 1, Int::plus)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                delay(RESIZE_SETTLE_MS)
                if (resizes[appWidgetId] == mine) deps.binderUpdater().update(intArrayOf(appWidgetId))
            } finally {
                pending.finish()
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)
            .configStore().remove(appWidgetIds)
    }

    private companion object {
        /** Resizes seen per widget, so a burst redraws once. */
        val resizes = ConcurrentHashMap<Int, Int>()

        const val RESIZE_SETTLE_MS = 400L
    }
}
