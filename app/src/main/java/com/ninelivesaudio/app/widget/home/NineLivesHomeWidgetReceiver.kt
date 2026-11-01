package com.ninelivesaudio.app.widget.home

import android.appwidget.AppWidgetManager
import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.ninelivesaudio.app.entitlement.EntitlementRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The Nine Lives widget's provider. Redraws come from the app's own events
 * through `WidgetRefresher`. The system's hourly update is a backstop: it
 * re-resolves entitlement first, so a trial that ended with the app closed
 * relocks here, the same as the Binder.
 */
class NineLivesHomeWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = NineLivesHomeWidget()

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun homeWidgetStore(): HomeWidgetStore
        fun entitlementRepository(): EntitlementRepository
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // Glance's own update holds the broadcast and runs the session, which
        // draws from the store and redraws when the refresh below lands.
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        val deps = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)
        backstopScope.launch {
            runCatching {
                deps.entitlementRepository().refresh()
                deps.homeWidgetStore().refresh()
            }.onFailure { Log.w(TAG, "backstop refresh failed: ${it.message}") }
        }
    }

    private companion object {
        const val TAG = "NineLivesHomeWidget"
        val backstopScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
