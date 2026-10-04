package com.ninelivesaudio.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The glow around a cover is skipped at zero progress, holds still when the
 * user wants less motion, and breathes the rest of the time.
 */
class FluorescentGlowModeTest {

    @Test
    fun `no progress draws nothing`() {
        assertEquals(GlowMode.Off, glowMode(progress = 0f, reduceMotion = false))
    }

    @Test
    fun `no progress draws nothing even when motion is reduced`() {
        assertEquals(GlowMode.Off, glowMode(progress = 0f, reduceMotion = true))
    }

    @Test
    fun `a progress that is not a number draws nothing`() {
        assertEquals(GlowMode.Off, glowMode(progress = Float.NaN, reduceMotion = false))
        assertEquals(GlowMode.Off, glowMode(progress = -0.2f, reduceMotion = false))
    }

    @Test
    fun `progress breathes by default`() {
        assertEquals(GlowMode.Breathing, glowMode(progress = 0.4f, reduceMotion = false))
        assertEquals(GlowMode.Breathing, glowMode(progress = 1f, reduceMotion = false))
    }

    @Test
    fun `progress holds still when motion is reduced`() {
        assertEquals(GlowMode.Still, glowMode(progress = 0.4f, reduceMotion = true))
    }

    @Test
    fun `the tiniest progress still counts`() {
        assertEquals(GlowMode.Breathing, glowMode(progress = 0.0001f, reduceMotion = false))
    }
}
