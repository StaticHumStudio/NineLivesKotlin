package com.ninelivesaudio.app.entitlement

import com.ninelivesaudio.app.domain.model.AppSettings
import com.ninelivesaudio.app.domain.model.ThemeMode
import com.ninelivesaudio.app.ui.library.SortMode
import com.ninelivesaudio.app.ui.library.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The free tier's shape, pinned. These sets are what the store listing promises,
 * so drift here is drift between the app and the thing people paid for.
 */
class FreeTierTest {

    // ─── Defaults must be free ────────────────────────────────────────────────

    /**
     * RECENTLY_PLAYED is the app's default sort in both UiState and
     * resetFilters(). If it were gated, a free install would open the Library
     * already sorted in a way it could not restore, and every entry point would
     * need an entitlement fork.
     */
    @Test
    fun `the default sort is free`() {
        assertTrue(FreeTier.allowsSort(SortMode.RECENTLY_PLAYED, isUnlocked = false))
    }

    @Test
    fun `the default view mode is free`() {
        assertTrue(FreeTier.allowsViewMode(ViewMode.ALL, isUnlocked = false))
    }

    /** The stored default theme must be reachable on a free install. */
    @Test
    fun `the default theme is free`() {
        assertEquals(FreeTier.THEME, AppSettings().themeMode)
        assertTrue(FreeTier.allowsTheme(AppSettings().themeMode, isUnlocked = false))
    }

    // ─── The free sets ────────────────────────────────────────────────────────

    @Test
    fun `free gets exactly three sorts`() {
        assertEquals(
            setOf(SortMode.RECENTLY_ADDED, SortMode.RECENTLY_PLAYED, SortMode.TITLE_AZ),
            FreeTier.SORT_MODES,
        )
    }

    @Test
    fun `every other sort is gated for free and open when unlocked`() {
        val gated = SortMode.entries - FreeTier.SORT_MODES

        assertEquals(8, gated.size)
        for (sort in gated) {
            assertFalse("$sort should be gated", FreeTier.allowsSort(sort, isUnlocked = false))
            assertTrue("$sort should open on unlock", FreeTier.allowsSort(sort, isUnlocked = true))
        }
    }

    @Test
    fun `grouping is gated and the flat list is not`() {
        for (mode in ViewMode.entries) {
            val expected = mode == ViewMode.ALL
            assertEquals("$mode free access", expected, FreeTier.allowsViewMode(mode, isUnlocked = false))
            assertTrue("$mode should open on unlock", FreeTier.allowsViewMode(mode, isUnlocked = true))
        }
    }

    @Test
    fun `only NOIR is free and every theme opens on unlock`() {
        for (theme in ThemeMode.entries) {
            val expected = theme == ThemeMode.NOIR
            assertEquals("$theme free access", expected, FreeTier.allowsTheme(theme, isUnlocked = false))
            assertTrue("$theme should open on unlock", FreeTier.allowsTheme(theme, isUnlocked = true))
        }
    }

    // ─── Unlocked is unconditional ────────────────────────────────────────────

    @Test
    fun `unlocked allows everything`() {
        SortMode.entries.forEach { assertTrue(FreeTier.allowsSort(it, isUnlocked = true)) }
        ViewMode.entries.forEach { assertTrue(FreeTier.allowsViewMode(it, isUnlocked = true)) }
        ThemeMode.entries.forEach { assertTrue(FreeTier.allowsTheme(it, isUnlocked = true)) }
    }

    // ─── Live downgrade of an already-selected option ─────────────────────────

    /**
     * Gating the control does not retire a choice already made. Someone who
     * picked a premium sort while unlocked must not keep it running behind a
     * greyed chip after a downgrade.
     */
    @Test
    fun `a gated sort falls back to the default when entitlement drops`() {
        assertEquals(
            SortMode.RECENTLY_PLAYED,
            FreeTier.effectiveSort(SortMode.DURATION_LONG, isUnlocked = false),
        )
    }

    @Test
    fun `a free sort survives a downgrade untouched`() {
        assertEquals(
            SortMode.TITLE_AZ,
            FreeTier.effectiveSort(SortMode.TITLE_AZ, isUnlocked = false),
        )
    }

    @Test
    fun `an unlocked install keeps whatever sort it chose`() {
        SortMode.entries.forEach {
            assertEquals(it, FreeTier.effectiveSort(it, isUnlocked = true))
        }
    }

    @Test
    fun `gated grouping falls back to the flat list on downgrade`() {
        assertEquals(ViewMode.ALL, FreeTier.effectiveViewMode(ViewMode.SERIES, isUnlocked = false))
        assertEquals(ViewMode.ALL, FreeTier.effectiveViewMode(ViewMode.ALL, isUnlocked = false))
        ViewMode.entries.forEach {
            assertEquals(it, FreeTier.effectiveViewMode(it, isUnlocked = true))
        }
    }

    /** The fallbacks must themselves be free, or a downgrade would loop. */
    @Test
    fun `the fallbacks are inside the free sets`() {
        assertTrue(FreeTier.effectiveSort(SortMode.DURATION_LONG, false) in FreeTier.SORT_MODES)
        assertTrue(FreeTier.effectiveViewMode(ViewMode.GENRE, false) in FreeTier.VIEW_MODES)
    }

    // ─── Normalization agrees with the catalog ────────────────────────────────

    /**
     * The gate and the engine must not disagree. If the UI greys a theme but
     * normalization leaves it applied, a downgraded user keeps a premium palette
     * behind a control that claims otherwise.
     */
    @Test
    fun `normalization pins a free install to the free theme`() {
        val stored = AppSettings(themeMode = ThemeMode.CANDLELIGHT)

        val effective = EffectiveSettings.normalize(stored, EntitlementState.FREE)

        assertEquals(FreeTier.THEME, effective.themeMode)
        assertTrue(FreeTier.allowsTheme(effective.themeMode, isUnlocked = false))
        // And the choice survives for the rebuy.
        assertEquals(ThemeMode.CANDLELIGHT, stored.themeMode)
    }

    @Test
    fun `an unlocked install keeps its chosen theme`() {
        val stored = AppSettings(themeMode = ThemeMode.CANDLELIGHT)
        val unlocked = EntitlementState(true, EntitlementSource.PLAY_UNLOCK)

        assertEquals(ThemeMode.CANDLELIGHT, EffectiveSettings.normalize(stored, unlocked).themeMode)
    }
}
