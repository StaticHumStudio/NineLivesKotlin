package com.ninelivesaudio.app.widget.binder

import com.ninelivesaudio.app.widget.WIDGET_BITMAP_BUDGET_BYTES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BinderLayoutTest {

    private fun shape(l: BinderLayout) = "${l.columnsPerSide}x${l.rows} ${l.arrows} labels=${l.labelLines}"

    // Pixel Launcher (pixel_6) portrait sizes, from the emulator's own reports.
    @Test fun `pixel launcher sizes get the layouts in the design note`() {
        assertEquals("1x1 EDGE labels=0", shape(binderLayoutFor(172, 108)))   // 2x1
        assertEquals("1x1 EDGE labels=0", shape(binderLayoutFor(266, 108)))   // 3x1
        assertEquals("2x1 EDGE labels=0", shape(binderLayoutFor(360, 108)))   // 4x1
        assertEquals("1x1 FOOTER labels=2", shape(binderLayoutFor(172, 233))) // 2x2
        assertEquals("1x1 FOOTER labels=2", shape(binderLayoutFor(266, 233))) // 3x2
        assertEquals("2x1 FOOTER labels=2", shape(binderLayoutFor(360, 233))) // 4x2
        assertEquals("1x2 FOOTER labels=2", shape(binderLayoutFor(172, 358))) // 2x3
        assertEquals("1x2 FOOTER labels=2", shape(binderLayoutFor(266, 358))) // 3x3
        assertEquals("2x2 FOOTER labels=2", shape(binderLayoutFor(360, 358))) // 4x3
    }

    // Estimates for the S26 Ultra's One UI grid (cells about 90 by 100 dp).
    @Test fun `one ui sizes stay sensible`() {
        assertEquals("1x1 EDGE labels=0", shape(binderLayoutFor(167, 86)))
        assertEquals("2x1 EDGE labels=0", shape(binderLayoutFor(357, 86)))
        assertEquals("2x1 FOOTER labels=2", shape(binderLayoutFor(357, 200)))
        assertEquals("2x2 FOOTER labels=2", shape(binderLayoutFor(357, 300)))
        assertEquals("2x2 FOOTER labels=2", shape(binderLayoutFor(357, 400)))
    }

    @Test fun `every disc a page takes is at least the comfortable size when the widget allows it`() {
        for (w in listOf(266, 360, 420)) for (h in listOf(108, 233, 358, 420)) {
            val l = binderLayoutFor(w, h)
            assertTrue("$w x $h -> $l", l.discDp >= BINDER_MIN_DISC_DP)
        }
    }

    @Test fun `a narrow tall binder adds a row when that costs no disc size`() {
        val l = binderLayoutFor(172, 358)
        assertEquals(2, l.rows)
        assertEquals(binderLayoutFor(172, 233).discDp, l.discDp)
    }

    @Test fun `a second line of title never costs a two row disc more than a few dp`() {
        for (w in listOf(172, 266, 357, 360)) for (h in listOf(300, 358, 400)) {
            val l = binderLayoutFor(w, h)
            if (l.rows < 2) continue
            val oneLine = pocketRoom(w, h, l.columnsPerSide, l.rows, l.arrows, labelLines = 1).disc
            assertTrue("$w x $h $l", l.discDp >= oneLine.toInt() - 4)
        }
    }

    @Test fun `the arrows move under the discs from the footer height`() {
        assertEquals(BinderArrows.EDGE, binderLayoutFor(360, BINDER_FOOTER_MIN_HEIGHT_DP - 1).arrows)
        assertEquals(BinderArrows.FOOTER, binderLayoutFor(360, BINDER_FOOTER_MIN_HEIGHT_DP).arrows)
    }

    @Test fun `a one row binder uses the compact chrome`() {
        val l = binderLayoutFor(172, 108)
        assertEquals(BinderChrome.COVER_COMPACT, l.coverPaddingDp)
        assertEquals(BinderChrome.COVER_ROOMY, binderLayoutFor(360, 233).coverPaddingDp)
    }

    @Test fun `the page number only shows where it fits between the arrows`() {
        assertFalse(binderLayoutFor(172, 233).showPageNumber)
        assertTrue(binderLayoutFor(266, 233).showPageNumber)
        assertFalse(binderLayoutFor(360, 108).showPageNumber)
    }

    @Test fun `pockets narrower than tall keep the disc and its title together`() {
        assertTrue(binderLayoutFor(360, 233).wrapPockets)
        assertFalse(binderLayoutFor(360, 108).wrapPockets)
    }

    @Test fun `a launcher that reports no size gets the smallest layout`() {
        assertEquals(BINDER_FALLBACK_LAYOUT, binderLayoutFor(0, 0))
        assertEquals(BINDER_FALLBACK_LAYOUT, binderLayoutFor(340, 0))
        assertEquals(2, BINDER_FALLBACK_LAYOUT.pocketsPerPage)
    }

    @Test fun `every layout's full page fits the bitmap budget`() {
        for (c in 1..2) for (r in 1..2) for (a in BinderArrows.entries) {
            val l = BinderLayout(c, r, a, 0)
            assertTrue("$l uses ${l.pageBitmapBytes}", l.pageBitmapBytes <= WIDGET_BITMAP_BUDGET_BYTES)
        }
        assertEquals(589_824, BinderLayout(2, 2, BinderArrows.FOOTER, 1).pageBitmapBytes)
    }

    @Test fun `pockets fill the left page first, row by row`() {
        val l = BinderLayout(2, 2, BinderArrows.FOOTER, 1)
        assertEquals(
            listOf(
                BinderSlot(BinderSide.LEFT, 0, 0), BinderSlot(BinderSide.LEFT, 0, 1),
                BinderSlot(BinderSide.LEFT, 1, 0), BinderSlot(BinderSide.LEFT, 1, 1),
                BinderSlot(BinderSide.RIGHT, 0, 0), BinderSlot(BinderSide.RIGHT, 0, 1),
                BinderSlot(BinderSide.RIGHT, 1, 0), BinderSlot(BinderSide.RIGHT, 1, 1),
            ),
            (0 until 8).map { l.slotOf(it) },
        )
        assertEquals(listOf(BinderSide.LEFT, BinderSide.RIGHT), (0 until 2).map { BINDER_FALLBACK_LAYOUT.slotOf(it).side })
    }
}
