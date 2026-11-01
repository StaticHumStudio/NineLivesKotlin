package com.ninelivesaudio.app.widget.binder

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.ninelivesaudio.app.R
import com.ninelivesaudio.app.entitlement.EffectiveSettingsRepository
import com.ninelivesaudio.app.service.SettingsManager
import com.ninelivesaudio.app.widget.WidgetAccess
import com.ninelivesaudio.app.widget.WidgetBookSource
import com.ninelivesaudio.app.widget.WidgetContent
import com.ninelivesaudio.app.widget.WidgetCopy
import com.ninelivesaudio.app.widget.WidgetLaunch
import com.ninelivesaudio.app.widget.widgetAccess
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/** Draws every Binder on the home screen from the app's current state. */
@Singleton
class BinderWidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsManager: SettingsManager,
    private val effectiveSettings: EffectiveSettingsRepository,
    private val configStore: BinderConfigStore,
    private val source: WidgetBookSource,
) {
    private val manager: AppWidgetManager get() = AppWidgetManager.getInstance(context)

    fun widgetIds(): IntArray =
        manager.getAppWidgetIds(ComponentName(context, BinderWidgetProvider::class.java))

    suspend fun updateAll() {
        val ids = widgetIds()
        if (ids.isNotEmpty()) update(ids)
    }

    suspend fun update(appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return
        val access = currentAccess()
        for (id in appWidgetIds) {
            val model = loadBinderModel(access, configStore.get(id), source)
            Log.d(TAG, "update $id ${model.content} spreads=${model.spreads.size}")
            manager.updateAppWidget(id, viewsFor(id, model))
        }
        manager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.binder_stack)
    }

    /** The model one Binder's stack draws, read fresh each time. */
    internal suspend fun model(appWidgetId: Int): Pair<BinderModel, BinderConfig> {
        val config = configStore.get(appWidgetId)
        return loadBinderModel(currentAccess(), config, source) to config
    }

    /**
     * Who may see what, once settings are loaded. A widget can wake the
     * process before `NineLivesApp` has read settings, and the defaults would
     * read as an empty local library.
     */
    private suspend fun currentAccess(): WidgetAccess {
        withTimeoutOrNull(5.seconds) { settingsManager.isLoaded.first { it } }
        return widgetAccess(
            isUnlocked = effectiveSettings.isUnlockedNow,
            settings = settingsManager.currentSettings,
            hasAuthToken = settingsManager.hasAuthToken.value,
        )
    }

    private fun viewsFor(appWidgetId: Int, model: BinderModel) = when (model.content) {
        WidgetContent.READY -> binderStackViews(context, appWidgetId)
        WidgetContent.LOCKED ->
            binderMessageViews(context, appWidgetId, WidgetCopy.BINDER_LOCKED, locked = true, WidgetLaunch.OpenUnlock)
        else -> binderMessageViews(
            context,
            appWidgetId,
            WidgetCopy.emptyLine(model.content, model.isLocalMode).orEmpty(),
            locked = false,
            WidgetLaunch.OpenApp,
        )
    }

    private companion object {
        const val TAG = "BinderWidget"
    }
}
