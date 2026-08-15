package com.ninelivesaudio.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure sizing-decision seam of the shared artwork downscale pipeline. The
 * decode/compress path itself touches android.graphics.Bitmap and isn't
 * unit-testable without Robolectric, but the sample-size math it depends on
 * is plain arithmetic.
 */
class ArtworkCodecTest {

    @Test
    fun `already within bounds needs no downsampling`() {
        assertEquals(1, ArtworkCodec.calculateInSampleSize(width = 256, height = 256, maxDimension = 512))
        assertEquals(1, ArtworkCodec.calculateInSampleSize(width = 512, height = 512, maxDimension = 512))
    }

    @Test
    fun `halves until both dimensions fit`() {
        // 2048x2048 -> 1024 -> 512 needs two halvings to get under 512.
        assertEquals(4, ArtworkCodec.calculateInSampleSize(width = 2048, height = 2048, maxDimension = 512))
    }

    @Test
    fun `uses the larger dimension to decide sampling`() {
        // A tall cover: width fits at 1x, height needs one halving.
        assertEquals(2, ArtworkCodec.calculateInSampleSize(width = 300, height = 900, maxDimension = 512))
    }

    @Test
    fun `browse thumbnail target downsamples more aggressively than now-playing`() {
        // Same source image, smaller target (256 vs 512) should never need
        // fewer halvings than the larger target.
        val source = 2048
        val browseSample = ArtworkCodec.calculateInSampleSize(source, source, maxDimension = 256)
        val nowPlayingSample = ArtworkCodec.calculateInSampleSize(source, source, maxDimension = 512)
        assert(browseSample >= nowPlayingSample) {
            "expected browse sampleSize ($browseSample) >= now-playing sampleSize ($nowPlayingSample)"
        }
    }
}
