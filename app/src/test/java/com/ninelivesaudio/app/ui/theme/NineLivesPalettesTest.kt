package com.ninelivesaudio.app.ui.theme

import androidx.compose.ui.graphics.Color
import com.ninelivesaudio.app.domain.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure mapping logic from ThemeMode to brand colors and Material color scheme.
 * No Compose runtime, no Android framework, so these run as plain JUnit.
 */
class NineLivesPalettesTest {

    @Test
    fun `every theme mode maps to a palette`() {
        // Exhaustive: nineLivesColorsFor must handle all enum values without
        // throwing, and each must resolve to a non-null holder.
        for (mode in ThemeMode.entries) {
            val colors = nineLivesColorsFor(mode)
            assertEquals(colors, nineLivesColorsFor(mode)) // stable
        }
    }

    @Test
    fun `noir is the default-look palette and is dark`() {
        val noir = nineLivesColorsFor(ThemeMode.NOIR)
        assertEquals(NoirColors, noir)
        assertFalse(noir.isLight)
    }

    @Test
    fun `amoled uses pure black for primary background and base surfaces`() {
        val amoled = nineLivesColorsFor(ThemeMode.AMOLED)
        assertEquals(Color(0xFF000000), amoled.archiveVoidDeep)
        assertEquals(Color(0xFF000000), amoled.archiveVoidBase)
        assertEquals(Color(0xFF000000), amoled.archiveNavRail)
        assertFalse(amoled.isLight)
    }

    @Test
    fun `amoled material scheme background is pure black`() {
        val scheme = colorSchemeFor(ThemeMode.AMOLED)
        assertEquals(Color(0xFF000000), scheme.background)
        assertEquals(Color(0xFF000000), scheme.surfaceContainerLowest)
    }

    @Test
    fun `bright is a real light theme`() {
        val bright = nineLivesColorsFor(ThemeMode.BRIGHT)
        assertTrue(bright.isLight)
        // Light background should be lighter than the dark text on it.
        assertTrue(bright.archiveVoidDeep.luminance() > bright.archiveTextPrimary.luminance())
    }

    @Test
    fun `candlelight is distinct from noir but still dark`() {
        val candle = nineLivesColorsFor(ThemeMode.CANDLELIGHT)
        val noir = nineLivesColorsFor(ThemeMode.NOIR)
        assertFalse(candle.isLight)
        assertNotEquals(noir.archiveVoidDeep, candle.archiveVoidDeep)
        assertNotEquals(noir.goldFilament, candle.goldFilament)
    }

    @Test
    fun `material scheme background tracks the palette for every theme`() {
        for (mode in ThemeMode.entries) {
            val colors = nineLivesColorsFor(mode)
            val scheme = colorSchemeFor(mode)
            assertEquals(
                "background mismatch for $mode",
                colors.archiveVoidDeep,
                scheme.background,
            )
            assertEquals(
                "primary mismatch for $mode",
                colors.goldFilament,
                scheme.primary,
            )
            assertEquals(
                "error mismatch for $mode",
                colors.archiveError,
                scheme.error,
            )
        }
    }

    // Crude perceptual luminance for the contrast assertion above. Avoids pulling
    // in Compose's Color.luminance() (which is fine on JVM, but keep the test
    // self-contained and dependency-light).
    private fun Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue
}
