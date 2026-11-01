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
            listOf(HomeLayout.CURRENT_ONLY, HomeLayout.RANK_AND_CURRENT, HomeLayout.FULL, HomeLayout.FULL),
            HOME_WIDGET_SIZES.map { (w, h) -> homeLayoutFor(w, h) },
        )
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
    }
}
