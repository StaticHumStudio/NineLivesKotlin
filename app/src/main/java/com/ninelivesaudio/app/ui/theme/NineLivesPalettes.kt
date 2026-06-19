package com.ninelivesaudio.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.ninelivesaudio.app.domain.model.ThemeMode

/**
 * Per-theme color definitions and the pure mapping from a [ThemeMode] to its
 * [NineLivesColors] holder and Material [ColorScheme].
 *
 * Keeping these as plain functions (no Compose, no Android) makes them unit
 * testable. The Theme composable resolves them once and provides the result.
 */

// ═══════════════════════════════════════════════════════════════
//  NOIR — Default Archive Beneath palette (deep indigo void, gold)
//  These values match the original top-level tokens exactly so the
//  default look is byte-for-byte unchanged for existing users.
// ═══════════════════════════════════════════════════════════════
val NoirColors = NineLivesColors(
    archiveVoidDeep = Color(0xFF0A0520),
    archiveVoidBase = Color(0xFF110A28),
    archiveVoidSurface = Color(0xFF1A1135),
    archiveVoidElevated = Color(0xFF251945),
    archiveNavRail = Color(0xFF05020F),

    goldFilament = Color(0xFFE8C468),
    goldFilamentBright = Color(0xFFF5D97A),
    goldFilamentDim = Color(0xFFB89650),
    goldFilamentFaint = Color(0xFF3D3520),
    goldFilamentGlow = Color(0xFFE8C468),

    impossibleAccent = Color(0xFF3DBFA0),

    archiveTextPrimary = Color(0xFFF5F5F7),
    archiveTextSecondary = Color(0xFFE0E0E5),
    archiveTextTertiary = Color(0xFFB0B0B8),
    archiveTextMuted = Color(0xFF707080),
    archiveTextFlavor = Color(0xFF8B8B95),

    archiveOutline = Color(0xFF242933),
    archiveDivider = Color(0xFF1A1F28),

    archiveSuccess = Color(0xFF4ADE80),
    archiveWarning = Color(0xFFFBBF24),
    archiveError = Color(0xFFF87171),
    archiveInfo = Color(0xFF60A5FA),
    archiveLocalAccent = Color(0xFFA78BFA),

    stoneSlab = Color(0xFF12161E),
    stoneRelic = Color(0xFF181D26),
    stoneAsh = Color(0xFF2A2F38),

    vintageLeather = Color(0xFF3B2415),
    vintageLeatherDark = Color(0xFF261308),
    vintageSpine = Color(0xFF4D3221),
    vintageBorder = Color(0xFF5C4030),
    vintageGoldFaded = Color(0xFF9B8455),
    vintageWear = Color(0xFF1A0E05),
    vintageTextGold = Color(0xFFC4A96A),

    isLight = false,
)

// ═══════════════════════════════════════════════════════════════
//  AMOLED — True-black dark theme for OLED battery savings.
//  Primary backgrounds and base surfaces are pure #000000. The gold
//  identity and accents are preserved.
// ═══════════════════════════════════════════════════════════════
val AmoledColors = NineLivesColors(
    archiveVoidDeep = Color(0xFF000000),
    archiveVoidBase = Color(0xFF000000),
    archiveVoidSurface = Color(0xFF0A0A0E),
    archiveVoidElevated = Color(0xFF161620),
    archiveNavRail = Color(0xFF000000),

    goldFilament = Color(0xFFE8C468),
    goldFilamentBright = Color(0xFFF5D97A),
    goldFilamentDim = Color(0xFFB89650),
    goldFilamentFaint = Color(0xFF2E2818),
    goldFilamentGlow = Color(0xFFE8C468),

    impossibleAccent = Color(0xFF3DBFA0),

    archiveTextPrimary = Color(0xFFF5F5F7),
    archiveTextSecondary = Color(0xFFDADADF),
    archiveTextTertiary = Color(0xFFA8A8B0),
    archiveTextMuted = Color(0xFF6E6E78),
    archiveTextFlavor = Color(0xFF85858F),

    archiveOutline = Color(0xFF1E1E26),
    archiveDivider = Color(0xFF141418),

    archiveSuccess = Color(0xFF4ADE80),
    archiveWarning = Color(0xFFFBBF24),
    archiveError = Color(0xFFF87171),
    archiveInfo = Color(0xFF60A5FA),
    archiveLocalAccent = Color(0xFFA78BFA),

    stoneSlab = Color(0xFF050507),
    stoneRelic = Color(0xFF0D0D12),
    stoneAsh = Color(0xFF1F1F26),

    vintageLeather = Color(0xFF3B2415),
    vintageLeatherDark = Color(0xFF261308),
    vintageSpine = Color(0xFF4D3221),
    vintageBorder = Color(0xFF5C4030),
    vintageGoldFaded = Color(0xFF9B8455),
    vintageWear = Color(0xFF120A04),
    vintageTextGold = Color(0xFFC4A96A),

    isLight = false,
)

// ═══════════════════════════════════════════════════════════════
//  BRIGHT — Genuine light theme. Warm off-white parchment surfaces,
//  dark ink text for daylight contrast, gold accent darkened to a
//  bronze so it stays legible on light backgrounds.
// ═══════════════════════════════════════════════════════════════
val BrightColors = NineLivesColors(
    archiveVoidDeep = Color(0xFFF5F2EA),
    archiveVoidBase = Color(0xFFFBF9F3),
    archiveVoidSurface = Color(0xFFFFFFFF),
    archiveVoidElevated = Color(0xFFEDE8DC),
    archiveNavRail = Color(0xFFEFEBE1),

    // Gold darkened to a readable bronze on light surfaces.
    goldFilament = Color(0xFF9A7B2E),
    goldFilamentBright = Color(0xFFB8923A),
    goldFilamentDim = Color(0xFF7A6126),
    goldFilamentFaint = Color(0xFFEAE0C2),
    goldFilamentGlow = Color(0xFFB8923A),

    impossibleAccent = Color(0xFF1F8C72),

    archiveTextPrimary = Color(0xFF1A160F),
    archiveTextSecondary = Color(0xFF40392C),
    archiveTextTertiary = Color(0xFF6A6253),
    archiveTextMuted = Color(0xFF8A8170),
    archiveTextFlavor = Color(0xFF7A7060),

    archiveOutline = Color(0xFFD8D0BE),
    archiveDivider = Color(0xFFE3DCCB),

    archiveSuccess = Color(0xFF1E9E54),
    archiveWarning = Color(0xFFB87E10),
    archiveError = Color(0xFFC4453B),
    archiveInfo = Color(0xFF2E6CC4),
    archiveLocalAccent = Color(0xFF6A4FB0),

    stoneSlab = Color(0xFFF0ECE1),
    stoneRelic = Color(0xFFE8E2D3),
    stoneAsh = Color(0xFFCFC8B6),

    vintageLeather = Color(0xFF8A5A38),
    vintageLeatherDark = Color(0xFF5C3A22),
    vintageSpine = Color(0xFF9C6A44),
    vintageBorder = Color(0xFFB08A5E),
    vintageGoldFaded = Color(0xFF9B8455),
    vintageWear = Color(0xFF6E4A2E),
    vintageTextGold = Color(0xFF7A6326),

    isLight = true,
)

// ═══════════════════════════════════════════════════════════════
//  CANDLELIGHT — Warm dark sepia variant. Same gold identity but the
//  void is brown-black ember instead of indigo, accents warm amber.
//  A candlelit reading-room feel, clearly distinct from NOIR's cool
//  indigo.
// ═══════════════════════════════════════════════════════════════
val CandlelightColors = NineLivesColors(
    archiveVoidDeep = Color(0xFF14100A),
    archiveVoidBase = Color(0xFF1C160D),
    archiveVoidSurface = Color(0xFF251D12),
    archiveVoidElevated = Color(0xFF332817),
    archiveNavRail = Color(0xFF0E0B06),

    goldFilament = Color(0xFFF0B860),
    goldFilamentBright = Color(0xFFFFD27A),
    goldFilamentDim = Color(0xFFC2914A),
    goldFilamentFaint = Color(0xFF3E2E16),
    goldFilamentGlow = Color(0xFFF0B860),

    impossibleAccent = Color(0xFFD46A3A),

    archiveTextPrimary = Color(0xFFFBF1DE),
    archiveTextSecondary = Color(0xFFEAD9BC),
    archiveTextTertiary = Color(0xFFC2AE8E),
    archiveTextMuted = Color(0xFF8A7A60),
    archiveTextFlavor = Color(0xFF9C8A6E),

    archiveOutline = Color(0xFF3A2E1C),
    archiveDivider = Color(0xFF2A2114),

    archiveSuccess = Color(0xFF7FB85A),
    archiveWarning = Color(0xFFE8A93A),
    archiveError = Color(0xFFE0705A),
    archiveInfo = Color(0xFFC9A24A),
    archiveLocalAccent = Color(0xFFC79A6A),

    stoneSlab = Color(0xFF1E1810),
    stoneRelic = Color(0xFF272016),
    stoneAsh = Color(0xFF3C3120),

    vintageLeather = Color(0xFF4A2E18),
    vintageLeatherDark = Color(0xFF2E1B0C),
    vintageSpine = Color(0xFF5E3E24),
    vintageBorder = Color(0xFF6E4C30),
    vintageGoldFaded = Color(0xFFB89860),
    vintageWear = Color(0xFF1F1208),
    vintageTextGold = Color(0xFFD4B070),

    isLight = false,
)

/** Pure mapping from a [ThemeMode] to its brand color holder. */
fun nineLivesColorsFor(mode: ThemeMode): NineLivesColors = when (mode) {
    ThemeMode.NOIR -> NoirColors
    ThemeMode.AMOLED -> AmoledColors
    ThemeMode.BRIGHT -> BrightColors
    ThemeMode.CANDLELIGHT -> CandlelightColors
}

/**
 * Pure mapping from a [ThemeMode] to a Material [ColorScheme].
 *
 * The Material scheme is derived from the same brand holder so any composable that
 * goes through MaterialTheme.colorScheme (rather than the brand accessor) recolors
 * with the theme too.
 */
fun colorSchemeFor(mode: ThemeMode): ColorScheme {
    val c = nineLivesColorsFor(mode)
    val base = if (c.isLight) lightColorScheme() else darkColorScheme()
    // The accent colors (gold, etc.) are light/mid-tone in every theme, so their
    // on-color must be dark ink in a light theme (see NineLivesColors.onAccent).
    val onAccent = c.onAccent
    return base.copy(
        primary = c.goldFilament,
        onPrimary = onAccent,
        primaryContainer = c.goldFilamentFaint,
        onPrimaryContainer = if (c.isLight) c.archiveTextPrimary else c.goldFilamentBright,

        secondary = c.archiveTextTertiary,
        onSecondary = onAccent,
        secondaryContainer = c.archiveVoidElevated,
        onSecondaryContainer = c.archiveTextSecondary,

        tertiary = c.impossibleAccent,
        onTertiary = onAccent,
        tertiaryContainer = c.archiveVoidElevated,
        onTertiaryContainer = c.impossibleAccent,

        background = c.archiveVoidDeep,
        onBackground = c.archiveTextPrimary,
        surface = c.archiveVoidBase,
        onSurface = c.archiveTextPrimary,
        surfaceVariant = c.archiveVoidSurface,
        onSurfaceVariant = c.archiveTextSecondary,
        surfaceContainerLowest = c.archiveVoidDeep,
        surfaceContainerLow = c.archiveVoidBase,
        surfaceContainer = c.archiveVoidSurface,
        surfaceContainerHigh = c.archiveVoidElevated,
        surfaceContainerHighest = c.archiveVoidElevated,

        error = c.archiveError,
        onError = c.archiveVoidDeep,
        errorContainer = c.archiveError.copy(alpha = 0.2f),
        onErrorContainer = c.archiveError,

        outline = c.archiveOutline,
        outlineVariant = c.archiveDivider,

        inverseSurface = c.archiveTextSecondary,
        inverseOnSurface = c.archiveVoidDeep,
        inversePrimary = c.goldFilamentDim,
    )
}
