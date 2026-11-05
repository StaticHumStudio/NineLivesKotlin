package com.ninelivesaudio.app.widget.binder

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
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

/** Which of the launcher's two orientations a Binder stack is drawn for. */
internal enum class BinderOrientation { PORTRAIT, LANDSCAPE }

/** Tells a stack's factory which orientation it feeds (BinderRemoteViewsService). */
internal const val EXTRA_BINDER_LANDSCAPE = "com.ninelivesaudio.app.widget.binder.LANDSCAPE"

/** The fingerprint of a stack's contents (binderStackVersion), from Android 15. */
internal const val EXTRA_BINDER_VERSION = "com.ninelivesaudio.app.widget.binder.VERSION"

/**
 * An open Binder. Before Android 15 it is drawn both ways round: the launcher
 * shows the portrait stack or the landscape one to match the home screen, and
 * each gets its own page layout for its own size and its own factory. From
 * Android 15 it is the portrait stack only ([binderSingleStack]). [layoutFor] picks the layout,
 * [books] decides whether a stack loops (binderStackLoops), and [versionFor]
 * gives each stack's contents fingerprint, or null before Android 15.
 * [displayedPage] turns the stacks to that page (an arrow tap). Left null,
 * a stack stays where it is.
 */
internal fun binderStackViews(
    context: Context,
    appWidgetId: Int,
    books: Int,
    layoutFor: (BinderOrientation) -> BinderLayout,
    versionFor: (BinderOrientation) -> Long?,
    displayedPage: Int? = null,
): RemoteViews {
    val portrait = binderStackViews(
        context, appWidgetId, BinderOrientation.PORTRAIT, layoutFor(BinderOrientation.PORTRAIT), books,
        versionFor(BinderOrientation.PORTRAIT), displayedPage,
    )
    if (binderSingleStack) return portrait
    val landscape = binderStackViews(
        context, appWidgetId, BinderOrientation.LANDSCAPE, layoutFor(BinderOrientation.LANDSCAPE), books,
        versionFor(BinderOrientation.LANDSCAPE), displayedPage,
    )
    return RemoteViews(landscape, portrait)
}

/**
 * Android 15 and up can gather a stack's pages into the update itself
 * (adapter conversion). There a portrait and landscape pair of stacks was
 * seen to draw discs from the other stack's pages under the right titles, so
 * from Android 15 the Binder is one stack laid out for the portrait size.
 * Before that it is the pair, and the launcher shows the one that matches.
 */
internal val binderSingleStack: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM

/** The orientations a Binder draws stacks for (see [binderSingleStack]). */
internal val binderOrientations: List<BinderOrientation>
    get() = if (binderSingleStack) listOf(BinderOrientation.PORTRAIT) else BinderOrientation.entries

/** One orientation's stack, fed by [BinderRemoteViewsService], under one fixed spine. */
private fun binderStackViews(
    context: Context,
    appWidgetId: Int,
    orientation: BinderOrientation,
    layout: BinderLayout,
    books: Int,
    version: Long?,
    displayedPage: Int?,
): RemoteViews = RemoteViews(
    context.packageName,
    if (binderStackLoops(binderPageCount(books, layout))) R.layout.widget_binder else R.layout.widget_binder_short,
).apply {
    val serviceIntent = Intent(context, BinderRemoteViewsService::class.java).apply {
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        putExtra(EXTRA_BINDER_LANDSCAPE, orientation == BinderOrientation.LANDSCAPE)
        // From Android 15 the contents' fingerprint, so new contents mean a new adapter (BinderWidgetUpdater).
        if (version != null) putExtra(EXTRA_BINDER_VERSION, version)
        // A distinct data URI per widget and orientation, so no two stacks share a factory.
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

/** The views of one pocket slot in widget_binder_page.xml. */
private class PocketIds(val cell: Int, val box: Int, val disc: Int, val title: Int, val label: Int)

/** binder_p0 to binder_p7: left page then right page, row by row, two a row (BinderLayout.slotOf). */
private val POCKETS = listOf(
    PocketIds(R.id.binder_p0_cell, R.id.binder_p0_box, R.id.binder_p0_disc, R.id.binder_p0_title, R.id.binder_p0_label),
    PocketIds(R.id.binder_p1_cell, R.id.binder_p1_box, R.id.binder_p1_disc, R.id.binder_p1_title, R.id.binder_p1_label),
    PocketIds(R.id.binder_p2_cell, R.id.binder_p2_box, R.id.binder_p2_disc, R.id.binder_p2_title, R.id.binder_p2_label),
    PocketIds(R.id.binder_p3_cell, R.id.binder_p3_box, R.id.binder_p3_disc, R.id.binder_p3_title, R.id.binder_p3_label),
    PocketIds(R.id.binder_p4_cell, R.id.binder_p4_box, R.id.binder_p4_disc, R.id.binder_p4_title, R.id.binder_p4_label),
    PocketIds(R.id.binder_p5_cell, R.id.binder_p5_box, R.id.binder_p5_disc, R.id.binder_p5_title, R.id.binder_p5_label),
    PocketIds(R.id.binder_p6_cell, R.id.binder_p6_box, R.id.binder_p6_disc, R.id.binder_p6_title, R.id.binder_p6_label),
    PocketIds(R.id.binder_p7_cell, R.id.binder_p7_box, R.id.binder_p7_disc, R.id.binder_p7_title, R.id.binder_p7_label),
)

/** The slot in widget_binder_page.xml that [slot] uses. */
internal fun pocketSlotIndex(slot: BinderSlot): Int =
    (if (slot.side == BinderSide.LEFT) 0 else 4) + slot.row * 2 + slot.column

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
    val twoRows = if (layout.rows > 1) View.VISIBLE else View.GONE
    setViewVisibility(R.id.binder_left_row1, twoRows)
    setViewVisibility(R.id.binder_right_row1, twoRows)

    val used = (0 until layout.pocketsPerPage).associateBy { pocketSlotIndex(layout.slotOf(it)) }
    POCKETS.forEachIndexed { slot, ids ->
        val index = used[slot]
        // A slot this size does not use: gone, and its tap does nothing. A second
        // column hides only when the layout has one; a second row hides as a row.
        if (index == null) {
            setViewVisibility(ids.cell, View.GONE)
            setOnClickFillInIntent(ids.cell, Intent().putWidgetLaunch(WidgetLaunch.Nothing))
            return@forEachIndexed
        }
        setViewVisibility(ids.cell, View.VISIBLE)
        pocket(context, layout, layout.slotOf(index).side, fills.getOrElse(index) { PocketFill.Empty }, ids)
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

private fun RemoteViews.pocket(context: Context, layout: BinderLayout, side: BinderSide, fill: PocketFill, ids: PocketIds) {
    val labels = layout.labelLines > 0
    // On a one row page the discs hug the spine, like a spread. With titles
    // under them they centre over their own title.
    val across = when {
        labels -> Gravity.CENTER_HORIZONTAL
        side == BinderSide.LEFT -> Gravity.END
        else -> Gravity.START
    }
    val down = if (labels) Gravity.BOTTOM else Gravity.CENTER_VERTICAL
    setInt(ids.box, "setGravity", across or down)
    val gap = context.dpPx(layout.pocketGapDp)
    setViewPadding(ids.cell, gap, gap, gap, gap + context.dpPx(layout.pocketBottomPadDp))

    when (fill) {
        is PocketFill.Disc -> {
            setImageViewBitmap(ids.disc, fill.bitmap)
            setViewVisibility(ids.title, View.GONE)
        }
        is PocketFill.TitleOnly -> {
            setImageViewResource(ids.disc, R.drawable.widget_binder_disc_blank)
            setViewVisibility(ids.title, if (labels) View.GONE else View.VISIBLE)
            setTextViewText(ids.title, fill.title)
        }
        PocketFill.Empty -> {
            setImageViewResource(ids.disc, R.drawable.widget_binder_pocket_empty)
            setViewVisibility(ids.title, View.GONE)
        }
    }
    val title = fill.title
    if (labels) {
        setViewVisibility(ids.label, View.VISIBLE)
        setInt(ids.label, "setLines", layout.labelLines)
        setTextViewText(ids.label, title.orEmpty())
    } else {
        setViewVisibility(ids.label, View.GONE)
    }
    setContentDescription(ids.cell, title)
    setOnClickFillInIntent(ids.cell, Intent().putWidgetLaunch(fill.clickLaunch()))
}

private fun Context.dpPx(dp: Int): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()
