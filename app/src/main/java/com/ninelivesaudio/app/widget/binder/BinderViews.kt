package com.ninelivesaudio.app.widget.binder

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.RemoteViews
import com.ninelivesaudio.app.R
import com.ninelivesaudio.app.widget.WidgetCopy
import com.ninelivesaudio.app.widget.WidgetLaunch
import com.ninelivesaudio.app.widget.putWidgetLaunch
import com.ninelivesaudio.app.widget.widgetActionIntent
import com.ninelivesaudio.app.widget.widgetMainIntent

/**
 * A closed Binder: nothing to flip through. The cover with the eye on it and
 * one line. Locked shows the lock and never any book data. The whole widget is one tap.
 */
internal fun binderMessageViews(
    context: Context,
    appWidgetId: Int,
    line: String,
    locked: Boolean,
    launch: WidgetLaunch,
): RemoteViews = RemoteViews(context.packageName, R.layout.widget_binder_message).apply {
    setTextViewText(R.id.binder_message, line)
    setViewVisibility(R.id.binder_lock, if (locked) View.VISIBLE else View.GONE)
    setContentDescription(R.id.binder_message_root, if (locked) WidgetCopy.CD_LOCKED else line)
    val pending = PendingIntent.getActivity(
        context,
        appWidgetId,
        widgetMainIntent(context, launch),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    setOnClickPendingIntent(android.R.id.background, pending)
}

/**
 * An open Binder: the StackView fed by [BinderRemoteViewsService], one factory
 * per widget, under one fixed spine. [displayedPage] turns the stack to that
 * page (an arrow tap). Left null, the stack stays where it is.
 */
internal fun binderStackViews(
    context: Context,
    appWidgetId: Int,
    layout: BinderLayout,
    displayedPage: Int? = null,
): RemoteViews = RemoteViews(context.packageName, R.layout.widget_binder).apply {
    val serviceIntent = Intent(context, BinderRemoteViewsService::class.java).apply {
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        // A distinct data URI per widget, so two Binders never share a factory.
        data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
    }
    @Suppress("DEPRECATION") // The collection items API is API 31+, and the factory streams covers.
    setRemoteAdapter(R.id.binder_stack, serviceIntent)
    setEmptyView(R.id.binder_stack, R.id.binder_stack_empty)
    val cover = context.dpPx(layout.coverPaddingDp)
    setViewPadding(android.R.id.background, cover, cover, cover, cover)
    val inset = context.dpPx(layout.pageInsetDp)
    setViewPadding(R.id.binder_spine_frame, inset, inset, inset, inset)
    // The fixed spine stops where the page's footer starts, so it never crosses the arrows.
    setViewVisibility(
        R.id.binder_spine_footer_gap,
        if (layout.arrows == BinderArrows.FOOTER) View.VISIBLE else View.GONE,
    )
    // Mutable is required so each pocket's and arrow's fill in intent can add its extras.
    // The intent is explicit, so nothing else can receive it.
    val template = PendingIntent.getActivity(
        context,
        appWidgetId,
        widgetActionIntent(context),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )
    setPendingIntentTemplate(R.id.binder_stack, template)
    if (displayedPage != null) setDisplayedChild(R.id.binder_stack, displayedPage)
}

/** What fills one pocket. */
internal sealed interface PocketFill {
    /** A disc printed with the cover, already baked (see [bakeBinderDisc]). */
    data class Disc(val bitmap: Bitmap, val title: String, val launch: WidgetLaunch) : PocketFill

    /** A book whose cover has not arrived: a blank disc and its title. */
    data class TitleOnly(val title: String, val launch: WidgetLaunch) : PocketFill

    /** An empty sleeve at the end of the last page. */
    data object Empty : PocketFill
}

/**
 * What a tap on this pocket asks for. Every fill has one, because a recycled
 * StackView row keeps its last fill in intent, and RemoteViews cannot clear it.
 */
internal fun PocketFill.clickLaunch(): WidgetLaunch = when (this) {
    is PocketFill.Disc -> launch
    is PocketFill.TitleOnly -> launch
    PocketFill.Empty -> WidgetLaunch.Nothing
}

private val PocketFill.title: String?
    get() = when (this) {
        is PocketFill.Disc -> title
        is PocketFill.TitleOnly -> title
        PocketFill.Empty -> null
    }

/**
 * One page of the stack at [layout]: the pockets on each side of the spine,
 * and the arrows that turn to the page before and after [position] of [count].
 */
internal fun binderPageViews(
    context: Context,
    appWidgetId: Int,
    layout: BinderLayout,
    fills: List<PocketFill>,
    position: Int,
    count: Int,
): RemoteViews = RemoteViews(context.packageName, R.layout.widget_binder_page).apply {
    val inset = context.dpPx(layout.pageInsetDp)
    setViewPadding(R.id.binder_page, inset, inset, inset, inset)
    // A recycled page keeps the pockets it was given last time.
    removeAllViews(R.id.binder_side_left)
    removeAllViews(R.id.binder_side_right)
    for (side in BinderSide.entries) {
        val sideId = if (side == BinderSide.LEFT) R.id.binder_side_left else R.id.binder_side_right
        for (row in 0 until layout.rows) {
            val rowViews = RemoteViews(context.packageName, R.layout.widget_binder_row)
            for (column in 0 until layout.columnsPerSide) {
                val index = (0 until layout.pocketsPerPage).first { layout.slotOf(it) == BinderSlot(side, row, column) }
                rowViews.addView(R.id.binder_row, pocketViews(context, layout, side, fills.getOrElse(index) { PocketFill.Empty }))
            }
            addView(sideId, rowViews)
        }
    }

    val turns = count > 1
    val edge = layout.arrows == BinderArrows.EDGE
    val back = binderTurnTarget(position, count, forward = false)
    val next = binderTurnTarget(position, count, forward = true)
    setViewVisibility(R.id.binder_edge_prev, if (edge) View.VISIBLE else View.GONE)
    setViewVisibility(R.id.binder_edge_next, if (edge) View.VISIBLE else View.GONE)
    setViewVisibility(R.id.binder_footer, if (edge) View.GONE else View.VISIBLE)
    val prevId = if (edge) R.id.binder_edge_prev else R.id.binder_prev
    val nextId = if (edge) R.id.binder_edge_next else R.id.binder_next
    // A one page Binder keeps the arrows' room, so its discs sit where they always do.
    setViewVisibility(prevId, if (turns) View.VISIBLE else View.INVISIBLE)
    setViewVisibility(nextId, if (turns) View.VISIBLE else View.INVISIBLE)
    setOnClickFillInIntent(prevId, binderTurnFillIn(appWidgetId, back))
    setOnClickFillInIntent(nextId, binderTurnFillIn(appWidgetId, next))
    setContentDescription(prevId, BinderCopy.CD_PREVIOUS)
    setContentDescription(nextId, BinderCopy.CD_NEXT)
    setTextViewText(R.id.binder_page_number, if (turns && layout.showPageNumber) binderPageNumber(position, count) else "")
}

private fun pocketViews(context: Context, layout: BinderLayout, side: BinderSide, fill: PocketFill): RemoteViews =
    RemoteViews(
        context.packageName,
        if (layout.wrapPockets) R.layout.widget_binder_pocket_wrap else R.layout.widget_binder_pocket,
    ).apply {
        val gap = context.dpPx(layout.pocketGapDp)
        setViewPadding(R.id.binder_pocket_cell, gap, gap, gap, gap)
        // On a one row page the discs hug the spine, like a spread. With titles
        // under them they centre over their own title.
        val across = when {
            layout.labelLines > 0 -> Gravity.CENTER_HORIZONTAL
            side == BinderSide.LEFT -> Gravity.END
            else -> Gravity.START
        }
        val down = if (layout.labelLines > 0) Gravity.BOTTOM else Gravity.CENTER_VERTICAL
        setInt(R.id.binder_pocket_box, "setGravity", across or down)

        val labels = layout.labelLines > 0
        when (fill) {
            is PocketFill.Disc -> {
                setImageViewBitmap(R.id.binder_pocket_disc, fill.bitmap)
                setViewVisibility(R.id.binder_pocket_title, View.GONE)
            }
            is PocketFill.TitleOnly -> {
                setImageViewResource(R.id.binder_pocket_disc, R.drawable.widget_binder_disc_blank)
                setViewVisibility(R.id.binder_pocket_title, if (labels) View.GONE else View.VISIBLE)
                setTextViewText(R.id.binder_pocket_title, fill.title)
            }
            PocketFill.Empty -> {
                setImageViewResource(R.id.binder_pocket_disc, R.drawable.widget_binder_pocket_empty)
                setViewVisibility(R.id.binder_pocket_title, View.GONE)
            }
        }
        val title = fill.title
        if (labels) {
            setViewVisibility(R.id.binder_pocket_label, View.VISIBLE)
            setInt(R.id.binder_pocket_label, "setLines", layout.labelLines)
            setTextViewText(R.id.binder_pocket_label, title.orEmpty())
        } else {
            setViewVisibility(R.id.binder_pocket_label, View.GONE)
        }
        setContentDescription(R.id.binder_pocket_cell, title)
        setOnClickFillInIntent(R.id.binder_pocket_cell, Intent().putWidgetLaunch(fill.clickLaunch()))
    }

private fun Context.dpPx(dp: Int): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()
