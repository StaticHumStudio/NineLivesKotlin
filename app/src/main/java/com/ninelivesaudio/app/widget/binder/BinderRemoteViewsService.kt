package com.ninelivesaudio.app.widget.binder

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.widget.BINDER_COVER_PX
import com.ninelivesaudio.app.widget.WidgetCoverLoader
import com.ninelivesaudio.app.widget.WidgetLaunch
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking

/** Feeds each Binder's StackView its spreads. */
class BinderRemoteViewsService : RemoteViewsService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun binderUpdater(): BinderWidgetUpdater
        fun coverLoader(): WidgetCoverLoader
    }

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        return BinderFactory(applicationContext, id, deps.binderUpdater(), deps.coverLoader())
    }
}

private class BinderFactory(
    private val context: Context,
    private val appWidgetId: Int,
    private val updater: BinderWidgetUpdater,
    private val covers: WidgetCoverLoader,
) : RemoteViewsService.RemoteViewsFactory {

    private var spreads: List<BinderSpread> = emptyList()
    private var books: Map<String, AudioBook> = emptyMap()
    private var tap: BinderTap = BinderTap.PLAY

    override fun onCreate() = Unit

    /** Runs on a binder thread, which may block. */
    override fun onDataSetChanged() {
        runBlocking {
            val (model, config) = updater.model(appWidgetId)
            covers.warm(model.books, BINDER_COVER_PX)
            spreads = model.spreads
            books = model.books.associateBy { it.id }
            tap = config.tap
        }
    }

    override fun onDestroy() {
        spreads = emptyList()
        books = emptyMap()
    }

    override fun getCount(): Int = spreads.size

    override fun getViewAt(position: Int): RemoteViews {
        val spread = spreads.getOrNull(position) ?: return placeholder()
        return binderSpreadViews(context, fillFor(spread.left), fillFor(spread.right))
    }

    private fun fillFor(page: BinderPage): SleeveFill = when (page) {
        BinderPage.Icon -> SleeveFill.Icon
        BinderPage.Empty -> SleeveFill.Empty
        is BinderPage.Book -> {
            val launch = when (tap) {
                BinderTap.PLAY -> WidgetLaunch.PlayBook(page.id)
                BinderTap.OPEN -> WidgetLaunch.OpenBook(page.id)
            }
            val bitmap = books[page.id]?.let { covers.cached(it, BINDER_COVER_PX) }
            if (bitmap != null) SleeveFill.Cover(bitmap, page.title, launch) else SleeveFill.TitleOnly(page.title, launch)
        }
    }

    private fun placeholder(): RemoteViews = binderSpreadViews(context, SleeveFill.Icon, SleeveFill.Empty)

    override fun getLoadingView(): RemoteViews = placeholder()

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = spreads.getOrNull(position)?.stableId ?: position.toLong()

    override fun hasStableIds(): Boolean = true
}
