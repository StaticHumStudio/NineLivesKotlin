package com.ninelivesaudio.app.service.cover

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class CoverImageFitterInstrumentedTest {

    private val fitter = CoverImageFitter()

    private fun png(w: Int, h: Int, color: Int = Color.rgb(40, 90, 160), border: Int = 0): ByteArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        if (border > 0) {
            c.drawColor(Color.WHITE)
            c.drawRect(border.toFloat(), border.toFloat(), (w - border).toFloat(), (h - border).toFloat(), Paint().apply { this.color = color })
        } else {
            c.drawColor(color)
        }
        val out = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.PNG, 100, out)
        b.recycle()
        return out.toByteArray()
    }

    private fun decode(bytes: ByteArray) = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!

    private fun luminance(c: Int) = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)

    @Test fun aPortraitCoverComesBackSquareAtItsOwnHeight() {
        val out = decode(fitter.fitToSquareJpeg(png(300, 500))!!)
        assertEquals(500, out.width)
        assertEquals(500, out.height)
    }

    @Test fun aSquareImageComesBackTheSameSize() {
        val out = decode(fitter.fitToSquareJpeg(png(400, 400))!!)
        assertEquals(400, out.width)
        assertEquals(400, out.height)
    }

    @Test fun aHugePhotoIsCappedAt1024() {
        val out = decode(fitter.fitToSquareJpeg(png(3000, 2000))!!)
        assertEquals(1024, out.width)
        assertEquals(1024, out.height)
    }

    @Test fun theFillIsDarkerThanTheArt() {
        val out = decode(fitter.fitToSquareJpeg(png(300, 500, Color.rgb(200, 40, 40)))!!)
        assertTrue(luminance(out.getPixel(5, 250)) < luminance(out.getPixel(250, 250)))
    }

    @Test fun theArtItselfIsNeverCropped() {
        // A 300x500 cover with a 4 px white frame sits at left = 100 on a 500 square.
        val out = decode(fitter.fitToSquareJpeg(png(300, 500, border = 4))!!)
        assertTrue("left frame", luminance(out.getPixel(101, 250)) > 200)
        assertTrue("right frame", luminance(out.getPixel(398, 250)) > 200)
        assertTrue("top frame", luminance(out.getPixel(250, 1)) > 200)
        assertTrue("bottom frame", luminance(out.getPixel(250, 498)) > 200)
        assertTrue("fill outside the frame", luminance(out.getPixel(95, 250)) < 150)
    }

    @Test fun bytesThatAreNotAnImageGiveNothing() =
        assertNull(fitter.fitToSquareJpeg("<html>".toByteArray()))
}
