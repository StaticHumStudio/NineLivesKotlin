package com.ninelivesaudio.app.widget.binder

import com.ninelivesaudio.app.widget.rgb565Bytes

/** Where a Binder page puts its page turn arrows. */
internal enum class BinderArrows {
    /** Slim arrows on the page's outer edges, for a one row widget with no room under the discs. */
    EDGE,

    /** 48dp arrows and a page number in a strip under the discs. */
    FOOTER,
}

/**
 * The Binder's fixed measurements in dp. The layouts' dimens match the roomy
 * set (widget_binder_dimens.xml). A one row Binder draws with the compact set
 * (paddings are set at draw time), because there every dp goes to the discs.
 */
internal object BinderChrome {
    const val COVER_ROOMY = 7
    const val COVER_COMPACT = 4
    const val INSET_ROOMY = 5
    const val INSET_COMPACT = 3
    const val GAP_ROOMY = 2
    const val GAP_COMPACT = 1
    const val SPINE_SLOT = 14
    const val EDGE_ARROW = 16
    const val FOOTER = 48
    const val FOOTER_ARROW = 56
    const val LABEL_TWO_LINES = 28
    const val LABEL_ONE_LINE = 15

    /** StackView draws its front page at 90% of its size, the rest is the fan of pages behind. */
    const val STACK_PAGE = 0.9f
}

/**
 * How one Binder page is laid out at the widget's current size. A page is a
 * spread: [columnsPerSide] by [rows] pockets on each side of the spine.
 * [labelLines] is how many lines of title sit under each disc (0 for none).
 * [discDp] is the disc size this layout works out to. [pocketBottomPadDp]
 * lifts a disc and its title off the bottom of a pocket that is taller than
 * it is wide, so the two sit together in the middle instead of leaving a gap
 * above the disc. [showPageNumber] is false when the strip between the
 * arrows is too narrow for "13 / 25".
 */
internal data class BinderLayout(
    val columnsPerSide: Int,
    val rows: Int,
    val arrows: BinderArrows,
    val labelLines: Int,
    val discDp: Int = 0,
    val pocketBottomPadDp: Int = 0,
    val showPageNumber: Boolean = false,
) {
    val pocketsPerSide: Int get() = columnsPerSide * rows
    val pocketsPerPage: Int get() = pocketsPerSide * 2
    val compact: Boolean get() = arrows == BinderArrows.EDGE

    val coverPaddingDp: Int get() = if (compact) BinderChrome.COVER_COMPACT else BinderChrome.COVER_ROOMY
    val pageInsetDp: Int get() = if (compact) BinderChrome.INSET_COMPACT else BinderChrome.INSET_ROOMY
    val pocketGapDp: Int get() = if (compact) BinderChrome.GAP_COMPACT else BinderChrome.GAP_ROOMY

    /**
     * The disc art size in pixels this layout would like. Fewer, bigger
     * pockets get sharper art, and a full page of discs always stays inside
     * the per page bitmap budget, because a launcher may fetch every page on
     * its own. [binderCoverPx] can lower it for the whole Binder.
     */
    val preferredCoverPx: Int
        get() = when {
            pocketsPerPage <= 2 -> 320
            pocketsPerPage <= 4 -> 256
            else -> 192
        }

    /** Bitmap bytes one full page carries at [preferredCoverPx]. */
    val pageBitmapBytes: Int get() = pocketsPerPage * rgb565Bytes(preferredCoverPx)

    /** Which side and slot pocket [index] of a page lands in, left page first, row by row. */
    fun slotOf(index: Int): BinderSlot {
        val side = if (index < pocketsPerSide) BinderSide.LEFT else BinderSide.RIGHT
        val inSide = index % pocketsPerSide
        return BinderSlot(side, row = inSide / columnsPerSide, column = inSide % columnsPerSide)
    }
}

internal enum class BinderSide { LEFT, RIGHT }

internal data class BinderSlot(val side: BinderSide, val row: Int, val column: Int)

/** Below this height (dp) there is no room under the discs, so the arrows go on the edges. */
internal const val BINDER_FOOTER_MIN_HEIGHT_DP = 140

/**
 * A page holds as many pockets as fit while every disc stays at least this
 * big (dp), a little more than a launcher icon, so the art still reads.
 */
internal const val BINDER_MIN_DISC_DP = 60

/** The layout when the launcher reports no size: the smallest, which fits anywhere. */
internal val BINDER_FALLBACK_LAYOUT = BinderLayout(columnsPerSide = 1, rows = 1, arrows = BinderArrows.EDGE, labelLines = 0)

/** One pocket's room in dp for the disc alone (titles already taken out): width, height. */
internal data class PocketRoom(val width: Float, val height: Float) {
    val disc: Float get() = minOf(width, height)
}

/**
 * Works out a pocket's room from the widget's size and the fixed measurements
 * in [BinderChrome]. [labelLines] defaults to two lines on one row, one on two.
 */
internal fun pocketRoom(
    widthDp: Int,
    heightDp: Int,
    columns: Int,
    rows: Int,
    arrows: BinderArrows,
    labelLines: Int = if (rows == 1) 2 else 1,
): PocketRoom {
    val c = BinderChrome
    val compact = arrows == BinderArrows.EDGE
    val cover = if (compact) c.COVER_COMPACT else c.COVER_ROOMY
    val inset = if (compact) c.INSET_COMPACT else c.INSET_ROOMY
    val gap = if (compact) c.GAP_COMPACT else c.GAP_ROOMY
    val pageW = (widthDp - 2 * cover) * c.STACK_PAGE - 2 * inset - c.SPINE_SLOT - (if (compact) 2 * c.EDGE_ARROW else 0)
    val pageH = (heightDp - 2 * cover) * c.STACK_PAGE - 2 * inset - (if (compact) 0 else c.FOOTER)
    val label = when {
        compact -> 0
        labelLines >= 2 -> c.LABEL_TWO_LINES
        else -> c.LABEL_ONE_LINE
    }
    return PocketRoom(
        width = pageW / (2 * columns) - 2 * gap,
        height = pageH / rows - 2 * gap - label,
    )
}

/**
 * The page layout for a widget [widthDp] by [heightDp] (the portrait size the
 * launcher reports). Height decides where the arrows go: on the edges of a
 * one row Binder, or full size under the discs, with titles, from about two
 * rows. Then the page takes as many pockets as fit (one or two a side, one or
 * two rows) while each disc stays at least [BINDER_MIN_DISC_DP]. Too small
 * for that, it keeps one disc a side, as big as it gets.
 *
 * On a One UI phone that works out to: 2x1 and 3x1 one disc a side, 4x1 two
 * a side, 2x2 and 3x2 one a side with titles, 4x2 two a side with titles,
 * 3x3 two rows of one, 4x3 two rows of two (eight a page).
 */
internal fun binderLayoutFor(widthDp: Int, heightDp: Int): BinderLayout {
    if (widthDp <= 0 || heightDp <= 0) return BINDER_FALLBACK_LAYOUT
    val arrows = if (heightDp >= BINDER_FOOTER_MIN_HEIGHT_DP) BinderArrows.FOOTER else BinderArrows.EDGE
    data class Option(val columns: Int, val rows: Int, val room: PocketRoom)
    val options = listOf(1 to 1, 2 to 1, 1 to 2, 2 to 2).map { (c, r) -> Option(c, r, pocketRoom(widthDp, heightDp, c, r, arrows)) }
    // Too small for the comfortable size, the bar drops to the biggest disc this
    // size allows, so a narrow tall Binder still adds a row when that costs nothing.
    val best = options.maxOf { it.room.disc }
    val bar = minOf(BINDER_MIN_DISC_DP.toFloat(), best - 0.5f)
    val pick = options.filter { it.room.disc >= bar }
        .maxWith(compareBy<Option>({ it.columns * it.rows }, { it.room.disc }))
    // Two row pages get two lines of title too, when that costs the disc next to nothing.
    val twoLineRoom = pocketRoom(widthDp, heightDp, pick.columns, pick.rows, arrows, labelLines = 2)
    val labels = when {
        arrows == BinderArrows.EDGE -> 0
        pick.rows == 1 -> 2
        twoLineRoom.disc >= pick.room.disc - TITLE_LINE_COST_DP -> 2
        else -> 1
    }
    val room = if (labels == 2) twoLineRoom else pick.room
    return BinderLayout(
        columnsPerSide = pick.columns,
        rows = pick.rows,
        arrows = arrows,
        labelLines = labels,
        discDp = room.disc.toInt().coerceAtLeast(0),
        showPageNumber = arrows == BinderArrows.FOOTER && footerTextRoom(widthDp) >= PAGE_NUMBER_MIN_DP,
        // Half the slack under the title centres the pair. Only with real slack, so
        // a title never gets squeezed by a disc that fills the height.
        // Less half a line, because most titles fill one of their two lines.
        pocketBottomPadDp = if (labels > 0 && room.height - room.width >= WRAP_SLACK_DP) {
            ((room.height - room.width) / 2 - HALF_TITLE_LINE_DP).toInt().coerceAtLeast(0)
        } else {
            0
        },
    )
}

/** How much disc (dp) a second line of title may cost on a two row page. */
private const val TITLE_LINE_COST_DP = 4

private const val HALF_TITLE_LINE_DP = 6

/** How much taller than wide a pocket must be before its disc and title are lifted to the middle. */
private const val WRAP_SLACK_DP = 8

/** Room for the page number between the two footer arrows, in dp. */
internal fun footerTextRoom(widthDp: Int): Float {
    val c = BinderChrome
    return (widthDp - 2 * c.COVER_ROOMY) * c.STACK_PAGE - 2 * c.INSET_ROOMY - 2 * c.FOOTER_ARROW
}

/** "13 / 25" in 11sp monospace, with a little air. */
private const val PAGE_NUMBER_MIN_DP = 44

/**
 * The disc art size for a whole Binder. Android 16 can bundle every page of
 * a widget's stack into one update, and that bundle's bitmaps must fit about
 * 90% of six bytes per screen pixel, shared by each stack the widget has
 * ([stacks]: one from Android 15, a portrait and a landscape one before). Past it, Android silently drops the later
 * pages. So the art shrinks, in steps of 16 px, until every page of [books]
 * books fits 80% of the stack's share. Never above [layout]'s preferred
 * size, never below 96 px. (The Binder never sends Android 15 and up a data
 * refresh, which would hold two copies: see BinderWidgetUpdater.)
 */
internal fun binderCoverPx(layout: BinderLayout, books: Int, screenPixels: Long, stacks: Int): Int {
    val preferred = layout.preferredCoverPx
    if (books <= 0 || screenPixels <= 0) return preferred
    val pocketsDrawn = ((books + layout.pocketsPerPage - 1) / layout.pocketsPerPage) * layout.pocketsPerPage
    val share = screenPixels * 6 * 9 / 10 / stacks.coerceAtLeast(1) * 8 / 10
    var px = preferred
    while (px > MIN_COVER_PX && pocketsDrawn.toLong() * rgb565Bytes(px) > share) px -= 16
    return px.coerceAtLeast(MIN_COVER_PX)
}

private const val MIN_COVER_PX = 96

/** How many pages [books] books make at [layout]. */
internal fun binderPageCount(books: Int, layout: BinderLayout): Int =
    if (books <= 0) 0 else (books + layout.pocketsPerPage - 1) / layout.pocketsPerPage

/**
 * Whether the stack loops (widget_binder.xml) or stops at its ends
 * (widget_binder_short.xml). StackView keeps five pages live, and with five
 * or fewer it fakes its loop with a window five times the page count, which a
 * fling past an end leaves half drawn. So only six pages and up loop. The
 * arrows wrap either way.
 */
internal fun binderStackLoops(pages: Int): Boolean = pages > STACK_LIVE_PAGES

private const val STACK_LIVE_PAGES = 5
