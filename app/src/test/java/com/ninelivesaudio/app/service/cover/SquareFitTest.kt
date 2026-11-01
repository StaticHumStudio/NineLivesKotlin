package com.ninelivesaudio.app.service.cover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SquareFitTest {

    @Test fun `a portrait cover sits centered on a square at its own height`() =
        assertEquals(SquareFit(side = 500, left = 91, top = 0, width = 318, height = 500, needsFill = true), squareFit(318, 500))

    @Test fun `a square cover fills the square with no fill`() =
        assertEquals(SquareFit(600, 0, 0, 600, 600, false), squareFit(600, 600))

    @Test fun `a big landscape photo shrinks to the cap and centers top to bottom`() =
        assertEquals(SquareFit(1024, 0, 128, 1024, 768, true), squareFit(4000, 3000))

    @Test fun `a tiny cover is never blown up`() =
        assertEquals(SquareFit(58, 11, 0, 36, 58, true), squareFit(36, 58))

    @Test fun `no size gives no fit`() {
        assertNull(squareFit(0, 500))
        assertNull(squareFit(-1, -1))
    }

    @Test fun `the decode target keeps the long side at the cap`() {
        assertEquals(1024 to 683, coverDecodeTarget(3000, 2000))
        assertEquals(318 to 500, coverDecodeTarget(318, 500))
    }
}
