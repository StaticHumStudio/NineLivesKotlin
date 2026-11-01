package com.ninelivesaudio.app.widget.home

import com.ninelivesaudio.app.widget.HOME_CURRENT_COVER_PX
import com.ninelivesaudio.app.widget.HOME_LIFE_COVER_PX
import com.ninelivesaudio.app.widget.rgb565Bytes

/**
 * What the Nine Lives widget keeps at a size. The design's priority order is
 * rank card, then current book, then the next Lives, and smaller sizes drop
 * from the bottom of that list: Lives first, then the rank card. The current
 * book with its play button is in every layout.
 */
internal enum class HomeLayout { CURRENT_ONLY, RANK_AND_CURRENT, FULL }

/** Width and height in dp, as Glance hands them to the layout. */
internal fun homeLayoutFor(widthDp: Float, heightDp: Float): HomeLayout = when {
    widthDp < 250f || heightDp < 180f -> HomeLayout.CURRENT_ONLY
    heightDp < 260f -> HomeLayout.RANK_AND_CURRENT
    else -> HomeLayout.FULL
}

/** How many next Lives fit (decision J3): three under 300dp wide, four from 300dp. */
internal fun livesSlotsFor(widthDp: Float): Int = if (widthDp < 300f) 3 else 4

/** Most Lives any layout shows. */
internal const val MAX_LIVES = 4

/**
 * The Glance `SizeMode.Responsive` buckets, width to height in dp. Glance
 * lays out each bucket once and the launcher picks the biggest that fits, so
 * the layout only ever sees these sizes. One starts at 300dp wide so a widget
 * that wide gets its four Lives (J3). The smallest matches the resize floor in
 * widget_home_info.xml, since a launcher rounds cells and a 2x2 or a one row
 * widget can come out under 180 by 110 (Pixel: about 172 by 99).
 */
internal val HOME_WIDGET_SIZES: List<Pair<Float, Float>> =
    listOf(160f to 80f, 160f to 180f, 250f to 180f, 250f to 260f, 300f to 260f)

/** A current only widget tall enough to stack the cover and play above the title (a 2x2). */
internal fun isTallCurrentOnly(widthDp: Float, heightDp: Float): Boolean =
    homeLayoutFor(widthDp, heightDp) == HomeLayout.CURRENT_ONLY && heightDp >= 180f

/** Cover bytes one bucket draws: the current book, plus the Lives only where they show. */
internal fun homeBucketBitmapBytes(widthDp: Float, heightDp: Float): Int {
    val lives = if (homeLayoutFor(widthDp, heightDp) == HomeLayout.FULL) livesSlotsFor(widthDp) else 0
    return rgb565Bytes(HOME_CURRENT_COVER_PX) + lives * rgb565Bytes(HOME_LIFE_COVER_PX)
}

/**
 * The most cover bytes one update can carry. API 30 sends one RemoteViews per
 * size and does not share a bitmap between them, so every bucket counts.
 */
internal fun homeWorstCaseBitmapBytes(): Int =
    HOME_WIDGET_SIZES.sumOf { (w, h) -> homeBucketBitmapBytes(w, h) }
