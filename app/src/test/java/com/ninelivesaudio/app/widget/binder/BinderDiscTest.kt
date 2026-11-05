package com.ninelivesaudio.app.widget.binder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BinderDiscTest {

    private val bar = 0xFF0A0520.toInt()
    private val art = 0xFFC08040.toInt()

    /** A [px] square cover with the art in [box] and bars everywhere else. */
    private fun cover(px: Int, box: ArtBounds): (Int, Int) -> Int = { x, y ->
        if (x in box.left until box.right && y in box.top until box.bottom) art else bar
    }

    @Test fun `a tall jacket's side bars are cut off`() {
        val box = ArtBounds(32, 0, 160, 192)
        assertEquals(box, fittedArtBounds(192, 192, cover(192, box)))
    }

    @Test fun `a wide cover's top and bottom bars are cut off`() {
        val box = ArtBounds(0, 40, 192, 152)
        assertEquals(box, fittedArtBounds(192, 192, cover(192, box)))
    }

    @Test fun `a square cover keeps all of its art`() {
        val all = ArtBounds(0, 0, 192, 192)
        assertEquals(all, fittedArtBounds(192, 192) { _, _ -> art })
    }

    @Test fun `a dark edge on one side only is part of the art`() {
        val oneSided: (Int, Int) -> Int = { x, _ -> if (x < 20) bar else art }
        assertEquals(ArtBounds(0, 0, 192, 192), fittedArtBounds(192, 192, oneSided))
    }

    @Test fun `an all dark cover is left whole`() {
        assertEquals(ArtBounds(0, 0, 192, 192), fittedArtBounds(192, 192) { _, _ -> bar })
    }

    @Test fun `the disc keeps its hole and hub inside the art`() {
        val g = discGeometry(256)
        assertTrue(g.holeRadius < g.hubRadius && g.hubRadius < g.discRadius)
        assertTrue(g.discRadius * 2 < 256)
        assertTrue(g.hubRadius / g.discRadius < 0.3f)
    }
}
