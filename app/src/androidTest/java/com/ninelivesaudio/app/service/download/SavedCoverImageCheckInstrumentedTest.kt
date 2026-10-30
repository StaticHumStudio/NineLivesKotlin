package com.ninelivesaudio.app.service.download

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * A refreshed cover replaces the one a download saved, so only bytes that
 * decode into a whole picture may do that (#70, review round 2). A header
 * that reads fine over broken picture data must be refused.
 */
@RunWith(AndroidJUnit4::class)
class SavedCoverImageCheckInstrumentedTest {

    private fun encoded(format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        for (x in 0 until 300) for (y in 0 until 300) bitmap.setPixel(x, y, Color.rgb(x % 256, y % 256, (x * y) % 256))
        return ByteArrayOutputStream().also { bitmap.compress(format, 90, it) }.toByteArray().also { bitmap.recycle() }
    }

    @Test
    fun wholeImagesPass() {
        assertTrue(decodesAsImage(encoded(Bitmap.CompressFormat.JPEG)))
        assertTrue(decodesAsImage(encoded(Bitmap.CompressFormat.PNG)))
    }

    @Test
    fun aSignInPageFails() {
        assertFalse(decodesAsImage("<html><body>Sign in</body></html>".toByteArray()))
        assertFalse(decodesAsImage(ByteArray(0)))
    }

    @Test
    fun aGoodHeaderOverBrokenPixelsFails() {
        val png = encoded(Bitmap.CompressFormat.PNG)
        // Everything up to the first IDAT chunk stays intact, so the size
        // reads fine. Only the compressed pixel data inside IDAT is wrecked.
        val idat = String(png, Charsets.ISO_8859_1).indexOf("IDAT")
        assertTrue(idat > 0)
        val length = ((png[idat - 4].toInt() and 0xFF) shl 24) or ((png[idat - 3].toInt() and 0xFF) shl 16) or
            ((png[idat - 2].toInt() and 0xFF) shl 8) or (png[idat - 1].toInt() and 0xFF)
        val broken = png.copyOf().also { for (i in idat + 4 + 2 until idat + 4 + length) it[i] = (it[i].toInt() xor 0x5A).toByte() }
        assertFalse(decodesAsImage(broken))
    }

    @Test
    fun aCutOffImageFails() {
        val jpeg = encoded(Bitmap.CompressFormat.JPEG)
        assertFalse(decodesAsImage(jpeg.copyOf(jpeg.size / 2)))
        val png = encoded(Bitmap.CompressFormat.PNG)
        assertFalse(decodesAsImage(png.copyOf(png.size / 2)))
    }
}
