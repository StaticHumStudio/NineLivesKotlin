package com.ninelivesaudio.app.ui.dossier

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ninelivesaudio.app.ui.theme.NineLivesAudioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #92: the shared Dossier image was a PixelCopy of whatever part of the
 * card was on screen, so a card scrolled half out came out cropped (and
 * anything drawn over it came along). The card now records into a graphics
 * layer and the share reads the whole layer.
 */
@RunWith(AndroidJUnit4::class)
class ShareCardCaptureTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var densityScale = 1f

    /**
     * A 400 dp viewport over a 600 dp spacer, a 700 dp blue card with a 100 dp
     * button under it, and room to scroll. Scrolled so only the card's last
     * 100 dp and the button show.
     */
    private fun captureScrolledCard(
        overlay: Boolean = false,
        hardwareImage: Bitmap? = null,
    ): Bitmap {
        lateinit var capture: ShareCardCapture
        lateinit var listState: LazyListState
        var scrollPx = 0
        composeRule.setContent {
            NineLivesAudioTheme {
                capture = rememberShareCardCapture()
                listState = rememberLazyListState()
                val density = LocalDensity.current
                densityScale = density.density
                scrollPx = with(density) { 600.dp.roundToPx() }
                Box(Modifier.fillMaxWidth().height(400.dp)) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        item { Spacer(Modifier.height(600.dp)) }
                        item {
                            Column {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(700.dp)
                                        .recordInto(capture)
                                        .background(Color.Blue)
                                        .testTag("card"),
                                ) {
                                    hardwareImage?.let {
                                        Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.size(64.dp))
                                    }
                                }
                                Box(Modifier.fillMaxWidth().height(100.dp).background(Color.Gray))
                            }
                        }
                        item { Spacer(Modifier.height(400.dp)) }
                    }
                    if (overlay) {
                        // Stands in for the whisper overlay, drawn over the card's visible slice.
                        Box(Modifier.fillMaxWidth().height(80.dp).background(Color.Red))
                    }
                }
            }
        }
        composeRule.runOnIdle { runBlocking { listState.scrollToItem(1, scrollPx) } }
        composeRule.waitForIdle()
        val bitmap = runBlocking(Dispatchers.Main) { capture.capture() }
        assertNotNull("the capture produced no image", bitmap)
        return bitmap!!
    }

    private fun dpToPx(dp: Int): Int = (dp * densityScale).toInt()

    private fun countPixels(bitmap: Bitmap, color: Int): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.count { it == color }
    }

    @Test
    fun aCardMostlyScrolledOutIsCapturedWhole() {
        val bitmap = captureScrolledCard()
        val card = composeRule.onNodeWithTag("card").fetchSemanticsNode().size

        assertEquals(card.width, bitmap.width)
        assertEquals(card.height, bitmap.height) // master's rect: about 100 dp
        // A row that is off screen.
        assertEquals(android.graphics.Color.BLUE, bitmap.getPixel(bitmap.width / 2, 2))
    }

    @Test
    fun somethingDrawnOverTheCardStaysOutOfTheImage() {
        val bitmap = captureScrolledCard(overlay = true)

        assertEquals(0, countPixels(bitmap, android.graphics.Color.RED))
        // The visible slice the overlay covered is still the card.
        assertEquals(android.graphics.Color.BLUE, bitmap.getPixel(bitmap.width / 2, bitmap.height - 2))
    }

    @Test
    fun aHardwareBitmapInsideTheCardDoesNotCrashTheCapture() {
        val software = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.RED)
        }
        val hardware = software.copy(Bitmap.Config.HARDWARE, false)
        assertEquals(Bitmap.Config.HARDWARE, hardware.config)

        val bitmap = captureScrolledCard(hardwareImage = hardware)

        assertNotEquals(Bitmap.Config.HARDWARE, bitmap.config)
        assertEquals(android.graphics.Color.RED, bitmap.getPixel(dpToPx(32), dpToPx(32)))
    }
}
