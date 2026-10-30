package com.ninelivesaudio.app.ui.dossier

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer

/**
 * Turns the Dossier share card into an image, whole, wherever it sits on screen.
 *
 * The card draws through [layer] ([recordInto]), and [capture] renders that
 * recording. Two older approaches failed:
 * - Software `View.draw` into a bitmap crashes on hardware bitmaps ("Software
 *   rendering doesn't support hardware bitmaps"), which Coil hands out for covers.
 * - PixelCopy of the window (2.1.x) copies only the pixels on screen, so a card
 *   scrolled part way out came out cropped (issue #92), and anything drawn over
 *   it, such as a whisper, came along.
 *
 * [GraphicsLayer.toImageBitmap] renders the recording through the hardware
 * renderer, so it sees the whole card, never what covers it, and draws hardware
 * bitmaps fine. Corners outside the card's rounded shape come out transparent.
 */
@Stable
internal class ShareCardCapture(internal val layer: GraphicsLayer) {

    /**
     * The card as a software ARGB_8888 bitmap, ready for PNG encoding, or null
     * when the card has not been drawn yet. Call on the main thread.
     */
    suspend fun capture(): Bitmap? {
        if (layer.isReleased || layer.size.width == 0 || layer.size.height == 0) return null
        val rendered = layer.toImageBitmap().asAndroidBitmap()
        if (rendered.config != Bitmap.Config.HARDWARE) return rendered
        val software = rendered.copy(Bitmap.Config.ARGB_8888, false)
        rendered.recycle()
        return software
    }
}

@Composable
internal fun rememberShareCardCapture(): ShareCardCapture {
    val layer = rememberGraphicsLayer()
    return remember(layer) { ShareCardCapture(layer) }
}

/** Draws this content through [capture]'s layer, so the share can render it whole. */
internal fun Modifier.recordInto(capture: ShareCardCapture): Modifier = drawWithContent {
    capture.layer.record { this@drawWithContent.drawContent() }
    drawLayer(capture.layer)
}
