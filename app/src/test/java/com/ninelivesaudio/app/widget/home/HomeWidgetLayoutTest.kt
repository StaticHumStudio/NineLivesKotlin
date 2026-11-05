package com.ninelivesaudio.app.widget.home

import com.ninelivesaudio.app.widget.WIDGET_BITMAP_BUDGET_BYTES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeWidgetLayoutTest {

    // Sizes as the launcher reports them, in dp. Pixel 6 (Pixel Launcher), as
    // logged on the emulator: it reports each size about 15dp taller than it
    // draws it. One UI from Jeff's S26 Ultra screenshot.
    private val pixel4x3 = 360f to 359f
    private val pixel3x3 = 266f to 359f
    private val pixel4x2 = 360f to 234f
    private val pixel3x2 = 266f to 234f
    private val pixel2x2 = 172f to 234f
    private val pixel2x1 = 172f to 99f
    private val oneUi4x3 = 400f to 340f
    private val oneUi4x4 = 400f to 453f

    private fun plan(size: Pair<Float, Float>, tiles: Int = 9) = homePlanFor(size.first, size.second, tiles)

    /** Height the plan's stacked face needs, smallest gaps, in dp. */
    private fun stackedHeight(p: HomePlan): Int {
        val altar = altarHeightDp(p.altar, p.eyeDp) + if (p.showPhrase) PHRASE_DP else 0
        return 2 * HOME_PAD_DP + altar + SECTION_GAP_DP + p.rows * p.tileDp + (p.rows - 1) * LIFE_GAP_DP + LINE_GAP_DP + TITLE_LINE_DP
    }

    @Test fun `the default 4x3 shows two rows of three Lives under the altar`() {
        for (size in listOf(pixel4x3, oneUi4x3)) {
            val p = plan(size)
            assertEquals("$size", HomeFace.STACKED, p.face)
            assertEquals("$size", 2, p.rows)
            assertEquals("$size", 6, p.slots)
        }
    }

    @Test fun `the 4x3 grid keeps nearly the full width`() {
        val p = plan(pixel4x3)
        val inner = pixel4x3.first - 2 * HOME_PAD_DP
        assertTrue("${p.gridWidthDp} of $inner", p.gridWidthDp >= 0.95f * inner)
    }

    @Test fun `a wide widget never buys the tall altar with empty side bands`() {
        // The S26 Ultra's screen faked on the emulator: a 4x3 draws 406 by 385dp.
        val p = plan(406f to 385f)
        assertEquals(AltarStyle.COMPACT, p.altar)
        assertEquals(2, p.rows)
        assertTrue("${p.gridWidthDp}", p.gridWidthDp >= 0.9f * (406 - 2 * HOME_PAD_DP))
    }

    @Test fun `a 3x3 gets Home's tall altar with the eye above the wordmark`() {
        val p = plan(pixel3x3)
        assertEquals(AltarStyle.TALL, p.altar)
        assertEquals(2, p.rows)
    }

    @Test fun `a taller widget gets the third row, Home's full nine`() {
        // At a Pixel 4x4 (about 478dp reported) two rows of 105 and three of 85
        // cover about the same area and two rows win. A little taller, three do.
        val p = plan(360f to 500f)
        assertEquals(AltarStyle.TALL, p.altar)
        assertEquals(3, p.rows)
        assertEquals(9, p.slots)
    }

    @Test fun `three rows only ever come with the tall altar`() {
        for (w in 220..520 step 20) for (h in 160..620 step 20) {
            val p = homePlanFor(w.toFloat(), h.toFloat())
            if (p.rows == 3) assertEquals("$w x $h", AltarStyle.TALL, p.altar)
        }
    }

    @Test fun `two rows high keeps the altar compact and one row of Lives`() {
        for (size in listOf(pixel4x2, pixel3x2)) {
            val p = plan(size)
            assertEquals("$size", HomeFace.STACKED, p.face)
            assertEquals("$size", AltarStyle.COMPACT, p.altar)
            assertEquals("$size", 1, p.rows)
        }
    }

    @Test fun `two cells wide shows LIFE I large under a mini altar`() {
        val p = plan(pixel2x2)
        assertEquals(HomeFace.NARROW, p.face)
        assertEquals(AltarStyle.MINI, p.altar)
        assertEquals(1, p.slots)
        assertTrue("tile ${p.tileDp}", p.tileDp >= 80)
    }

    @Test fun `one cell high keeps LIFE I and its disc in a row`() {
        val p = plan(pixel2x1)
        assertEquals(HomeFace.ROW, p.face)
        assertEquals(AltarStyle.NONE, p.altar)
        assertEquals((99 * (1 - REPORTED_SIZE_SLACK) - 2 * HOME_PAD_DP).toInt(), p.tileDp)
    }

    @Test fun `a short history gets bigger tiles, not empty rows`() {
        val full = plan(pixel4x3, tiles = 9)
        val few = plan(pixel4x3, tiles = 2)
        assertEquals(1, few.rows)
        assertTrue(few.tileDp >= full.tileDp)
        // With the room a second row would have taken, Home's tall altar comes back with its line.
        assertEquals(AltarStyle.TALL, few.altar)
        assertTrue(few.showPhrase)
    }

    @Test fun `every stacked plan fits its height and width, with no tile below the floor`() {
        for (w in 230..520 step 10) for (h in 160..620 step 10) for (tiles in listOf(1, 4, 9)) {
            val p = homePlanFor(w.toFloat(), h.toFloat(), tiles)
            if (p.face != HomeFace.STACKED) continue
            assertTrue("$w x $h h", stackedHeight(p) <= h * (1 - REPORTED_SIZE_SLACK))
            assertTrue("$w x $h w", p.gridWidthDp <= w - 2 * HOME_PAD_DP)
            assertTrue("$w x $h tile", p.tileDp in MIN_TILE_DP..MAX_TILE_DP)
        }
    }

    @Test fun `the narrow and row faces fit too`() {
        for (w in 160..224 step 4) for (h in 176..500 step 8) {
            val p = homePlanFor(w.toFloat(), h.toFloat())
            assertEquals("$w x $h", HomeFace.NARROW, p.face)
            val altar = if (p.altar == AltarStyle.MINI) altarHeightDp(AltarStyle.MINI) + SECTION_GAP_DP else 0
            assertTrue("$w x $h", altar + p.tileDp + LINE_GAP_DP + TITLE_BLOCK_DP <= h * (1 - REPORTED_SIZE_SLACK) - 2 * HOME_PAD_DP)
            assertTrue("$w x $h", p.tileDp <= w - 2 * HOME_PAD_DP)
        }
        for (w in 160..520 step 20) {
            val p = homePlanFor(w.toFloat(), 80f)
            assertEquals(HomeFace.ROW, p.face)
            assertEquals((80 * (1 - REPORTED_SIZE_SLACK) - 2 * HOME_PAD_DP).toInt(), p.tileDp)
        }
    }

    @Test fun `the row face always leaves the title its room`() {
        // Codex round 1: a 160 by 168 widget drew a 131dp cover in a 132dp row.
        for (w in 160..520 step 4) for (h in 80..168 step 4) {
            val p = homePlanFor(w.toFloat(), h.toFloat())
            if (p.face != HomeFace.ROW) continue
            assertTrue("$w x $h", p.tileDp + ROW_GAP_DP + ROW_TITLE_MIN_DP <= w - 2 * HOME_PAD_DP)
            assertTrue("$w x $h", p.tileDp >= 1)
        }
        assertEquals(160 - 2 * HOME_PAD_DP - ROW_GAP_DP - ROW_TITLE_MIN_DP, homePlanFor(160f, 168f).tileDp)
    }

    @Test fun `the One UI tall cell shape still fills, tall altar and two big rows`() {
        val p = plan(oneUi4x4)
        assertEquals(AltarStyle.TALL, p.altar)
        assertTrue(p.rows >= 2)
        assertTrue(p.tileDp >= 100)
    }

    @Test fun `the tiles take the height before the eye grows`() {
        val p = plan(pixel3x3)
        assertEquals(AltarStyle.TALL, p.altar)
        // Width limited: (266 - 28 - 16) / 3.
        assertEquals(74, p.tileDp)
        assertTrue(p.eyeDp in MIN_EYE_DP..MID_EYE_DP)
    }

    @Test fun `the tall altar spends its slack on the eye, then the line, then the eye again`() {
        assertEquals(MIN_EYE_DP to false, tallAltarExtras(0))
        assertEquals(MIN_EYE_DP to false, tallAltarExtras(-20))
        assertEquals(40 to false, tallAltarExtras(8))
        // Not enough for the line: the rest grows the eye instead.
        assertEquals(MAX_EYE_DP to false, tallAltarExtras(16 + PHRASE_DP - 1))
        assertEquals(MID_EYE_DP to true, tallAltarExtras(16 + PHRASE_DP))
        assertEquals(MAX_EYE_DP to true, tallAltarExtras(200))
        val roomy = plan(pixel4x3, tiles = 2)
        assertEquals(MAX_EYE_DP, roomy.eyeDp)
        assertTrue(roomy.showPhrase)
    }

    @Test fun `a launcher that reports a few percent too tall still fits`() {
        // Pixel Launcher reports its 4x3 as 359dp high and draws it 344dp high.
        val p = homePlanFor(360f, 359f)
        assertTrue(stackedHeight(p) <= 344)
    }

    @Test fun `life labels are Home's roman numerals`() {
        assertEquals("LIFE I", lifeLabel(0))
        assertEquals("LIFE IV", lifeLabel(3))
        assertEquals("LIFE IX", lifeLabel(8))
        assertEquals("LIFE 10", lifeLabel(9))
    }

    @Test fun `tracked caps put a thin space between letters`() {
        assertEquals("N\u2009O\u2009V\u2009I\u2009C\u2009E", trackedCaps("Novice"))
        assertEquals("N\u2009I\u2009N\u2009E\u2009 \u2009L\u2009I\u2009V\u2009E\u2009S", WORDMARK)
        assertEquals("", trackedCaps(""))
        assertEquals("L\u200AI\u200AF\u200AE\u200A \u200AI\u200AI", trackedLabel(lifeLabel(1)))
    }

    @Test fun `the disc is about a third of the tile, within bounds`() {
        assertEquals(MIN_BADGE_DP, badgeDp(50))
        assertEquals(37, badgeDp(104))
        assertEquals(MAX_BADGE_DP, badgeDp(200))
    }

    @Test fun `one update stays inside the bitmap budget with all nine Lives`() {
        assertEquals(192 * 192 * 2 + 8 * 176 * 176 * 2, homeUpdateBitmapBytes())
        assertTrue(homeUpdateBitmapBytes() <= WIDGET_BITMAP_BUDGET_BYTES)
        assertFalse(MAX_LIVES + 1 > MAX_LIFE_ROWS * LIFE_COLUMNS)
    }
}
