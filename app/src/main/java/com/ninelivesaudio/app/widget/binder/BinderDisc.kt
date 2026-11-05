package com.ninelivesaudio.app.widget.binder

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader

/** archiveVoidBase, a Binder page (widget_binder_page_bg.xml). Outside the pocket's corners. */
internal const val BINDER_PAGE_COLOR = 0xFF110A28.toInt()

/** archiveVoidDeep, the inside of a pocket (widget_binder_pocket_empty.xml). */
internal const val BINDER_POCKET_COLOR = 0xFF0A0520.toInt()

/**
 * Where the parts of a disc land in a [px] pocket, as fractions of [px] so
 * the vector sleeve drawn over it (viewport 100) lines up at any size.
 */
internal data class DiscGeometry(
    val px: Int,
    /** Pocket corner radius. Matches the sleeve's 4 of 100. */
    val pocketCorner: Float,
    val discRadius: Float,
    /** The clear plastic hub around the hole, where the print stops. */
    val hubRadius: Float,
    val holeRadius: Float,
    val rimWidth: Float,
)

/** A CD's proportions, with the hub kept small so the art keeps its middle. */
internal fun discGeometry(px: Int): DiscGeometry {
    val p = px.toFloat()
    val disc = p * 0.46f
    return DiscGeometry(
        px = px,
        pocketCorner = p * 0.04f,
        discRadius = disc,
        hubRadius = disc * 0.24f,
        holeRadius = disc * 0.11f,
        rimWidth = (p * 0.012f).coerceAtLeast(1f),
    )
}

/**
 * Prints a square [cover] onto a disc in a pocket: the page colour outside
 * the pocket's rounded corners, the pocket, the disc with the art, a clear
 * hub and the spindle hole. Opaque RGB_565, like every widget bitmap
 * (WidgetCovers.kt), so the corners are baked in the page's colour.
 * The caller keeps [cover]. The plastic sleeve's edge, lip and sheen are a
 * vector over this (widget_binder_sleeve.xml), so they stay sharp.
 */
internal fun bakeBinderDisc(cover: Bitmap, px: Int): Bitmap {
    val g = discGeometry(px)
    val out = Bitmap.createBitmap(px, px, Bitmap.Config.RGB_565)
    val c = Canvas(out)
    val cx = px / 2f
    val cy = px / 2f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    c.drawColor(BINDER_PAGE_COLOR)
    paint.color = BINDER_POCKET_COLOR
    c.drawRoundRect(RectF(0f, 0f, px.toFloat(), px.toFloat()), g.pocketCorner, g.pocketCorner, paint)

    // The art, scaled so the disc covers it, centred. A tall jacket or a wide
    // cover was fitted whole into the cached square with sleeve colour bars at
    // its sides (WidgetCovers.fitInSquare). A disc is printed edge to edge, so
    // it takes the middle square of the art itself instead of the bars.
    val art = fittedArtBounds(cover.width, cover.height) { x, y -> cover.getPixel(x, y) }
    val side = minOf(art.width, art.height).toFloat()
    val left = art.left + (art.width - side) / 2f
    val top = art.top + (art.height - side) / 2f
    val d = g.discRadius * 2
    val m = Matrix().apply {
        setTranslate(-left, -top)
        postScale(d / side, d / side)
        postTranslate(cx - g.discRadius, cy - g.discRadius)
    }
    paint.shader = BitmapShader(cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(m) }
    c.drawCircle(cx, cy, g.discRadius, paint)
    paint.shader = null

    // The clear outer edge every pressed disc has.
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = g.rimWidth
    paint.color = 0x38FFFFFF
    c.drawCircle(cx, cy, g.discRadius - g.rimWidth / 2, paint)

    // The clear hub: the art shows faintly through the plastic.
    paint.style = Paint.Style.FILL
    paint.color = 0x99141024.toInt()
    c.drawCircle(cx, cy, g.hubRadius, paint)
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = (g.rimWidth * 0.8f).coerceAtLeast(1f)
    paint.color = 0x40FFFFFF
    c.drawCircle(cx, cy, g.hubRadius, paint)
    paint.color = 0x26FFFFFF
    c.drawCircle(cx, cy, (g.hubRadius + g.holeRadius) / 2, paint)

    // The spindle hole: the pocket shows through it.
    paint.style = Paint.Style.FILL
    paint.color = BINDER_POCKET_COLOR
    c.drawCircle(cx, cy, g.holeRadius, paint)
    paint.style = Paint.Style.STROKE
    paint.color = 0x4DFFFFFF
    c.drawCircle(cx, cy, g.holeRadius, paint)
    return out
}

/** The art's box inside a cover square, in pixels, right and bottom exclusive. */
internal data class ArtBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** archiveVoidDeep, the colour WidgetCoverLoader fills a fitted cover's bars with. */
private const val FIT_BAR_COLOR = 0xFF0A0520.toInt()

/**
 * Where the art sits inside a cached [width] by [height] cover square: the
 * whole square, unless the cover was fitted with bars of [FIT_BAR_COLOR] down
 * both sides (a tall jacket) or across top and bottom (a wide cover). Rows
 * and columns are sampled away from the baked rounded corners. Bars must be
 * on both sides and about the same width, so a dark edge on an ordinary
 * square cover is left alone.
 */
internal fun fittedArtBounds(width: Int, height: Int, pixelAt: (Int, Int) -> Int): ArtBounds {
    val whole = ArtBounds(0, 0, width, height)
    if (width < 16 || height < 16) return whole
    val samples = 9
    fun isBar(c: Int): Boolean {
        val dr = ((c shr 16) and 0xFF) - ((FIT_BAR_COLOR shr 16) and 0xFF)
        val dg = ((c shr 8) and 0xFF) - ((FIT_BAR_COLOR shr 8) and 0xFF)
        val db = (c and 0xFF) - (FIT_BAR_COLOR and 0xFF)
        return dr * dr + dg * dg + db * db <= BAR_TOLERANCE * BAR_TOLERANCE
    }
    fun columnIsBar(x: Int) = (0 until samples).all { i -> isBar(pixelAt(x, height / 4 + i * (height / 2) / (samples - 1))) }
    fun rowIsBar(y: Int) = (0 until samples).all { i -> isBar(pixelAt(width / 4 + i * (width / 2) / (samples - 1), y)) }
    fun run(limit: Int, test: (Int) -> Boolean): Int {
        var n = 0
        while (n < limit && test(n)) n++
        return n
    }
    val limit = minOf(width, height) / 3
    val l = run(limit) { columnIsBar(it) }
    val r = run(limit) { columnIsBar(width - 1 - it) }
    if (l in MIN_BAR until limit && r in MIN_BAR until limit && kotlin.math.abs(l - r) <= BAR_SLACK) return ArtBounds(l, 0, width - r, height)
    val t = run(limit) { rowIsBar(it) }
    val b = run(limit) { rowIsBar(height - 1 - it) }
    if (t in MIN_BAR until limit && b in MIN_BAR until limit && kotlin.math.abs(t - b) <= BAR_SLACK) return ArtBounds(0, t, width, height - b)
    return whole
}

private const val BAR_TOLERANCE = 14
private const val MIN_BAR = 4
private const val BAR_SLACK = 3
