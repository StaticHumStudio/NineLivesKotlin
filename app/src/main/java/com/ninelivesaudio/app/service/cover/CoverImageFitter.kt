package com.ninelivesaudio.app.service.cover

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/** Turns a chosen image into the square JPEG the app keeps as the book's cover. */
@Singleton
class CoverImageFitter @Inject constructor() {

    /** Width and height from the header only, or null when the bytes are not an image. */
    fun decodeSize(bytes: ByteArray): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
    }

    /**
     * The image on a square at its own long side (at most [COVER_MAX_SIDE]), never cropped.
     * A non square image sits over a soft fill made from its own art (scaled to a few
     * pixels and back up, then darkened). EXIF rotation applies, so a phone photo comes
     * out upright. Null on any failure.
     */
    fun fitToSquareJpeg(bytes: ByteArray): ByteArray? {
        val made = mutableListOf<Bitmap>()
        return try {
            val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
            val art = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val (w, h) = coverDecodeTarget(info.size.width, info.size.height)
                if (w != info.size.width || h != info.size.height) decoder.setTargetSize(w, h)
            }.also { made += it }
            val fit = squareFit(art.width, art.height) ?: return null
            val out = Bitmap.createBitmap(fit.side, fit.side, Bitmap.Config.ARGB_8888).also { made += it }
            val canvas = Canvas(out)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            if (fit.needsFill) {
                val scale = 16f / max(art.width, art.height)
                val tiny = Bitmap.createScaledBitmap(
                    art,
                    (art.width * scale).roundToInt().coerceAtLeast(1),
                    (art.height * scale).roundToInt().coerceAtLeast(1),
                    true,
                ).also { made += it }
                canvas.drawBitmap(tiny, null, Rect(0, 0, fit.side, fit.side), paint)
                canvas.drawColor(Color.argb((COVER_FILL_DARKEN * 255).roundToInt(), 0, 0, 0))
            }
            canvas.drawBitmap(art, null, Rect(fit.left, fit.top, fit.left + fit.width, fit.top + fit.height), paint)
            val stream = ByteArrayOutputStream()
            if (!out.compress(Bitmap.CompressFormat.JPEG, COVER_JPEG_QUALITY, stream)) return null
            stream.toByteArray()
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        } finally {
            made.distinct().forEach { runCatching { it.recycle() } }
        }
    }
}
