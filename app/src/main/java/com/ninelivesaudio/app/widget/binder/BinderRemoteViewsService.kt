package com.ninelivesaudio.app.widget.binder

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.widget.WidgetCoverLoader
import com.ninelivesaudio.app.widget.WidgetLaunch
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking

/** Feeds each Binder's StackView its pages. */
class BinderRemoteViewsService : RemoteViewsService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun binderUpdater(): BinderWidgetUpdater
        fun coverLoader(): WidgetCoverLoader
    }

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val orientation =
            if (intent.getBooleanExtra(EXTRA_BINDER_LANDSCAPE, false)) BinderOrientation.LANDSCAPE else BinderOrientation.PORTRAIT
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        return BinderFactory(applicationContext, id, orientation, deps.binderUpdater(), deps.coverLoader())
    }
}

private class BinderFactory(
    private val context: Context,
    private val appWidgetId: Int,
    private val orientation: BinderOrientation,
    private val updater: BinderWidgetUpdater,
    private val covers: WidgetCoverLoader,
) : RemoteViewsService.RemoteViewsFactory {

    private var pages: List<BinderPage> = emptyList()
    private var books: Map<String, AudioBook> = emptyMap()
    private var tap: BinderTap = BinderTap.PLAY
    private var layout: BinderLayout = BINDER_FALLBACK_LAYOUT
    private var coverPx: Int = BINDER_FALLBACK_LAYOUT.preferredCoverPx

    override fun onCreate() = Unit

    /**
     * Runs on a binder thread, which may block. Synchronized with the reads
     * below, so a page is never cut from one layout and drawn with another
     * when two fetches of the stack overlap.
     */
    @Synchronized
    override fun onDataSetChanged() {
        runBlocking {
            val (model, config) = updater.model(appWidgetId)
            val size = updater.layoutFor(appWidgetId, orientation)
            val px = updater.coverPxFor(appWidgetId, orientation, model.books.size)
            covers.warm(model.books, px)
            layout = size
            coverPx = px
            pages = if (model.books.isEmpty()) emptyList() else binderPages(model.books, size.pocketsPerPage)
            books = model.books.associateBy { it.id }
            tap = config.tap
        }
    }

    override fun onDestroy() {
        pages = emptyList()
        books = emptyMap()
    }

    @Synchronized
    override fun getCount(): Int = pages.size

    @Synchronized
    override fun getViewAt(position: Int): RemoteViews {
        val page = pages.getOrNull(position) ?: return placeholder()
        return binderPageViews(context, appWidgetId, layout, page.pockets.map(::fillFor), position, pages.size)
    }

    private fun fillFor(pocket: BinderPocket): PocketFill = when (pocket) {
        BinderPocket.Empty -> PocketFill.Empty
        is BinderPocket.Book -> {
            val launch = when (tap) {
                BinderTap.PLAY -> WidgetLaunch.PlayBook(pocket.id)
                BinderTap.OPEN -> WidgetLaunch.OpenBook(pocket.id)
            }
            val disc = books[pocket.id]?.let { covers.cached(it, coverPx) }?.let { cover ->
                try {
                    bakeBinderDisc(cover, coverPx)
                } finally {
                    cover.recycle()
                }
            }
            if (disc != null) PocketFill.Disc(disc, pocket.title, launch) else PocketFill.TitleOnly(pocket.title, launch)
        }
    }

    /** A page of empty sleeves, while the real one loads. */
    private fun placeholder(): RemoteViews =
        binderPageViews(context, appWidgetId, layout, List(layout.pocketsPerPage) { PocketFill.Empty }, 0, 1)

    override fun getLoadingView(): RemoteViews = placeholder()

    override fun getViewTypeCount(): Int = 1

    @Synchronized
    override fun getItemId(position: Int): Long = pages.getOrNull(position)?.stableId ?: position.toLong()

    override fun hasStableIds(): Boolean = true
}
