package com.ninelivesaudio.app.widget.home

import com.ninelivesaudio.app.widget.WIDGET_BITMAP_BUDGET_BYTES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeWidgetLayoutTest {

    @Test fun `smaller sizes drop the lives first, then the rank card`() {
        assertEquals(HomeLayout.FULL, homeLayoutFor(320f, 270f))
        assertEquals(HomeLayout.RANK_AND_CURRENT, homeLayoutFor(320f, 259f))
        assertEquals(HomeLayout.CURRENT_ONLY, homeLayoutFor(320f, 179f))
        assertEquals(HomeLayout.CURRENT_ONLY, homeLayoutFor(249f, 400f))
    }

    @Test fun `every responsive bucket maps to a layout that keeps play`() {
        assertEquals(
            listOf(HomeLayout.CURRENT_ONLY, HomeLayout.CURRENT_ONLY, HomeLayout.RANK_AND_CURRENT, HomeLayout.FULL, HomeLayout.FULL),
            HOME_WIDGET_SIZES.map { (w, h) -> homeLayoutFor(w, h) },
        )
    }

    @Test fun `the smallest bucket is the resize floor and keeps play`() {
        assertEquals(160f to 80f, HOME_WIDGET_SIZES.first())
        assertEquals(HomeLayout.CURRENT_ONLY, homeLayoutFor(160f, 80f))
    }

    @Test fun `wider widgets show four lives`() {
        assertEquals(3, livesSlotsFor(299f))
        assertEquals(4, livesSlotsFor(300f))
    }

    @Test fun `the four lives breakpoint is a responsive bucket, so a 300dp widget gets four`() {
        // Glance hands the layout the bucket's size, not the real one, so the
        // J3 breakpoint only holds if some bucket starts exactly there.
        assertTrue(HOME_WIDGET_SIZES.any { (w, h) -> w == 300f && homeLayoutFor(w, h) == HomeLayout.FULL })
        assertEquals(3, HOME_WIDGET_SIZES.filter { homeLayoutFor(it.first, it.second) == HomeLayout.FULL }.minOf { livesSlotsFor(it.first) })
    }

    @Test fun `the large widget stays inside the bitmap budget at every size`() {
        assertTrue(homeWorstCaseBitmapBytes() <= WIDGET_BITMAP_BUDGET_BYTES)
        // Lives only count where they are drawn.
        assertEquals(192 * 192 * 2, homeBucketBitmapBytes(250f, 180f))
        assertEquals(192 * 192 * 2 + 4 * 128 * 128 * 2, homeBucketBitmapBytes(300f, 260f))
    }

    @Test fun `a 2x2 stacks the current book, a short one keeps it in a row`() {
        assertTrue(isTallCurrentOnly(160f, 180f))
        assertTrue(!isTallCurrentOnly(160f, 80f))
        assertTrue(!isTallCurrentOnly(250f, 180f))
    }

    @Test fun `the lives share the row width evenly, square, with 10dp gaps`() {
        // 12dp padding a side, so the tiles and their gaps span the width inside it.
        assertEquals((300f - 24f - 3 * 10f) / 4, lifeTileDp(300f, 4), 0.01f)
        assertEquals((250f - 24f - 2 * 10f) / 3, lifeTileDp(250f, 3), 0.01f)
        // A very wide widget stops growing the tiles, so the column still fits its height.
        assertEquals(MAX_LIFE_TILE_DP, lifeTileDp(600f, 3), 0.01f)
    }

    @Test fun `the full face fits its smallest height with the tiles at their size`() {
        // Rank slab 56, current book 64, rule 1 plus its 10dp gap, the tiles, 12dp padding a side.
        for ((w, h) in HOME_WIDGET_SIZES.filter { homeLayoutFor(it.first, it.second) == HomeLayout.FULL }) {
            assertTrue("$w x $h", 56f + 64f + 11f + lifeTileDp(w, livesSlotsFor(w)) + 24f <= h)
        }
    }
}
