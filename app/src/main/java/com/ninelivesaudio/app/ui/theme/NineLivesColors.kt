package com.ninelivesaudio.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Brand color holder for the Nine Lives Audio app.
 *
 * The app's identity uses many brand-specific tones (gold filament, void surfaces,
 * stone textures, vintage book cover palette) that do not map cleanly onto the
 * standard Material color roles. Rather than hardcode those tones as top-level
 * vals, every theme provides a fully populated [NineLivesColors] holder. Composables
 * read them through [NineLivesTheme.colors], so swapping the active theme recolors
 * the whole app.
 *
 * Field names mirror the original top-level token names (GoldFilament ->
 * goldFilament, ArchiveVoidDeep -> archiveVoidDeep, etc.) so the migration from
 * direct val references is a mechanical change of color source, not of meaning.
 *
 * All instances are immutable, so reading from the CompositionLocal is cheap and
 * does not invalidate composition unnecessarily.
 */
@Immutable
data class NineLivesColors(
    // Void surfaces (backgrounds, cards, panels, nav rail)
    val archiveVoidDeep: Color,
    val archiveVoidBase: Color,
    val archiveVoidSurface: Color,
    val archiveVoidElevated: Color,
    val archiveNavRail: Color,

    // Gold filament accent family
    val goldFilament: Color,
    val goldFilamentBright: Color,
    val goldFilamentDim: Color,
    val goldFilamentFaint: Color,
    val goldFilamentGlow: Color,

    // Impossible accent (focus / selection only)
    val impossibleAccent: Color,

    // Text
    val archiveTextPrimary: Color,
    val archiveTextSecondary: Color,
    val archiveTextTertiary: Color,
    val archiveTextMuted: Color,
    val archiveTextFlavor: Color,

    // Outlines / borders / dividers
    val archiveOutline: Color,
    val archiveDivider: Color,

    // Semantic status
    val archiveSuccess: Color,
    val archiveWarning: Color,
    val archiveError: Color,
    val archiveInfo: Color,
    val archiveLocalAccent: Color,

    // Stone texture tones (surface composables)
    val stoneSlab: Color,
    val stoneRelic: Color,
    val stoneAsh: Color,

    // Vintage book placeholder cover palette
    val vintageLeather: Color,
    val vintageLeatherDark: Color,
    val vintageSpine: Color,
    val vintageBorder: Color,
    val vintageGoldFaded: Color,
    val vintageWear: Color,
    val vintageTextGold: Color,

    // True when this theme has a light background. Drives system-bar icon polarity
    // and any place that needs to know light vs dark beyond raw colors.
    val isLight: Boolean,
) {
    /** Focus outline always tracks the impossible accent, as in the original palette. */
    val archiveFocusOutline: Color get() = impossibleAccent
}
