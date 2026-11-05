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
import com.ninelivesaudio.app.widget.WidgetCoverLoader
import com.ninelivesaudio.app.widget.WidgetCopy
import com.ninelivesaudio.app.widget.WidgetLaunch
import com.ninelivesaudio.app.widget.widgetAccess
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    private val covers: WidgetCoverLoader,
) {
    private val manager: AppWidgetManager get() = AppWidgetManager.getInstance(context)

    fun widgetIds(): IntArray =
        manager.getAppWidgetIds(ComponentName(context, BinderWidgetProvider::class.java))

    suspend fun updateAll() {
        val ids = widgetIds()
        if (ids.isNotEmpty()) update(ids)
    }

    /**
     * One redraw at a time. On Android 16 each update also gathers every page
     * of the stack from the factory, and a resize fires a burst of updates;
     * overlapping ones were seen to leave a page's discs out of step with its
     * titles until the next redraw.
     */
    private val drawLock = Mutex()

    suspend fun update(appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return
        withContext(Dispatchers.IO) { drawLock.withLock { updateLocked(appWidgetIds) } }
    }

    private suspend fun updateLocked(appWidgetIds: IntArray) {
        val access = currentAccess()
        for (id in appWidgetIds) {
            val config = configStore.get(id)
            val model = loadBinderModel(access, config, source)
            Log.d(
                TAG,
                "update $id ${model.content} books=${model.books.size} " +
                    "portrait=${sizeDp(id, BinderOrientation.PORTRAIT)} ${layoutFor(id, BinderOrientation.PORTRAIT)} " +
                    if (binderSingleStack) "single stack" else "landscape=${sizeDp(id, BinderOrientation.LANDSCAPE)} ${layoutFor(id, BinderOrientation.LANDSCAPE)}",
            )
            manager.updateAppWidget(id, viewsFor(id, model, config))
        }
        // From Android 15 the stacks' versions already made any changed stack
        // build afresh in the update above. A data refresh there would gather
        // the pages again into the saved views while keeping the old bitmaps,
        // which was seen to draw discs from another layout under the right
        // titles, and doubles the bitmaps against the widget's cap.
        if (!versionedStacks) manager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.binder_stack)
    }

    /** Android 15 and up can gather a stack's pages into the update itself (adapter conversion). */
    private val versionedStacks: Boolean get() = binderSingleStack

    /** The disc art size one stack draws at, for [books] books (binderCoverPx). */
    internal fun coverPxFor(appWidgetId: Int, orientation: BinderOrientation, books: Int): Int {
        val metrics = context.resources.displayMetrics
        return binderCoverPx(
            layoutFor(appWidgetId, orientation),
            books,
            metrics.widthPixels.toLong() * metrics.heightPixels,
            stacks = binderOrientations.size,
        )
    }

    /**
     * Each stack's contents fingerprint, from Android 15 (null before). With
     * [fetch], missing covers are fetched first, the same as the stack's
     * factory does, so the fingerprint knows which covers it will draw. A page
     * turn does not fetch, so an arrow never waits on the network.
     */
    private suspend fun stackVersions(
        appWidgetId: Int,
        model: BinderModel,
        config: BinderConfig,
        fetch: Boolean = true,
    ): Map<BinderOrientation, Long>? {
        if (!versionedStacks) return null
        return binderOrientations.associateWith { orientation ->
            val layout = layoutFor(appWidgetId, orientation)
            val px = coverPxFor(appWidgetId, orientation, model.books.size)
            if (fetch) covers.warm(model.books, px)
            val keys = model.books.map { "${it.id}|${it.title}|${covers.fileName(it, px)}|${covers.isCached(it, px)}" }
            binderStackVersion(keys, config.tap, layout, px)
        }
    }

    /**
     * The page layout for the size the launcher reports in [orientation].
     * Portrait is the minimum width and the maximum height, landscape the
     * maximum width and the minimum height (AppWidgetManager's options).
     */
    internal fun layoutFor(appWidgetId: Int, orientation: BinderOrientation): BinderLayout {
        val (w, h) = sizeDp(appWidgetId, orientation)
        return binderLayoutFor(w, h)
    }

    private fun sizeDp(appWidgetId: Int, orientation: BinderOrientation): Pair<Int, Int> {
        val o = manager.getAppWidgetOptions(appWidgetId)
        return when (orientation) {
            BinderOrientation.PORTRAIT ->
                o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) to o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            BinderOrientation.LANDSCAPE ->
                o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH) to o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        }
    }

    /**
     * An arrow tap: turn the stack to [page]. A full update, because a partial
     * one would be merged into the saved views and lose the turn (BinderTurn.kt).
     * The stack's pages are not rebuilt. The shelf is read only to know the
     * page count (whether the stack loops) and that the Binder is still open.
     * One that is not (the trial ended, signed out, emptied) gets its proper face.
     */
    suspend fun turnTo(appWidgetId: Int, page: Int) = withContext(Dispatchers.IO) {
        drawLock.withLock {
            val config = configStore.get(appWidgetId)
            val model = loadBinderModel(currentAccess(), config, source)
            if (model.content != WidgetContent.READY) {
                updateLocked(intArrayOf(appWidgetId))
                return@withLock
            }
            Log.d(TAG, "turn $appWidgetId to $page")
            val versions = stackVersions(appWidgetId, model, config, fetch = false)
            manager.updateAppWidget(
                appWidgetId,
                binderStackViews(
                    context, appWidgetId, model.books.size, { layoutFor(appWidgetId, it) }, { versions?.get(it) },
                    displayedPage = page,
                ),
            )
        }
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

    private suspend fun viewsFor(appWidgetId: Int, model: BinderModel, config: BinderConfig) = when (model.content) {
        WidgetContent.READY -> {
            val versions = stackVersions(appWidgetId, model, config)
            binderStackViews(context, appWidgetId, model.books.size, { layoutFor(appWidgetId, it) }, { versions?.get(it) })
        }
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
