package com.ninelivesaudio.app.ui.theme.unhinged

import androidx.compose.ui.graphics.Color

/**
 * Archive Beneath Color Palette
 *
 * The Unhinged theme palette based on the "Archive Beneath" aesthetic:
 * - Deep obsidian/indigo backgrounds (not pure black)
 * - Gold filament accents (warm metallic for primary interactive elements)
 * - Impossible accent (verdigris or magenta - for focus/selection only)
 * - Outline/glow brushes for borders and focus indicators
 *
 * All colors are tuned for WCAG AA contrast (4.5:1 for body text, 3:1 for large text).
 */

// ═══════════════════════════════════════════════════════════════
//  Archive Beneath — Void Surfaces (Deep INDIGO, not pure black)
// ═══════════════════════════════════════════════════════════════

/** Deepest background - deep indigo void */
val ArchiveVoidDeep = Color(0xFF0A0520)

/** Base surface - indigo obsidian */
val ArchiveVoidBase = Color(0xFF110A28)

/** Standard surface - for cards and panels */
val ArchiveVoidSurface = Color(0xFF1A1135)

/** Elevated surface - for modals and overlays */
val ArchiveVoidElevated = Color(0xFF251945)

/** Navigation rail background - darker than deep */
val ArchiveNavRail = Color(0xFF05020F)

// ═══════════════════════════════════════════════════════════════
//  Gold Filament — Primary Interactive Accent (BRIGHTER)
// ═══════════════════════════════════════════════════════════════

/** Primary gold accent - warm metallic (brighter than cosmic) */
val GoldFilament = Color(0xFFE8C468)

/** Bright gold - for hover states and emphasis */
val GoldFilamentBright = Color(0xFFF5D97A)

/** Dim gold - for disabled or subdued states */
val GoldFilamentDim = Color(0xFFB89650)

/** Faint gold - for subtle backgrounds */
val GoldFilamentFaint = Color(0xFF3D3520)

/** Glow gold - for glow/shadow effects (use with alpha) */
val GoldFilamentGlow = Color(0xFFE8C468) // Use at 0.25 alpha

// ═══════════════════════════════════════════════════════════════
//  Impossible Accent — Focus & Selection ONLY
// ═══════════════════════════════════════════════════════════════

/**
 * The "impossible" accent color - used ONLY for:
 * - Focus visual borders (thin outline)
 * - Selected list items (small edge highlight, 2-3dp)
 * - Progress bars (thin filament accent line)
 *
 * NEVER use for:
 * - Body text foreground
 * - Full background fills
 * - More than ~5% of visible screen area
 *
 * Gold remains the primary UI language. This is the exception.
 */
val ImpossibleAccent = Color(0xFF3DBFA0) // Muted verdigris
// Alternative option: val ImpossibleAccent = Color(0xFFC850C0) // Magenta

// ═══════════════════════════════════════════════════════════════
//  Text — Tuned for Dark Surfaces
// ═══════════════════════════════════════════════════════════════

/** Primary text - high contrast white */
val ArchiveTextPrimary = Color(0xFFF5F5F7)

/** Secondary text - slightly dimmed */
val ArchiveTextSecondary = Color(0xFFE0E0E5)

/** Tertiary text - subdued but readable */
val ArchiveTextTertiary = Color(0xFFB0B0B8)

/** Muted text - for placeholders and hints */
val ArchiveTextMuted = Color(0xFF707080)

/** Flavor text - for whispers and subtitles (lower contrast) */
val ArchiveTextFlavor = Color(0xFF8B8B95)

// ═══════════════════════════════════════════════════════════════
//  Outlines, Borders, Dividers
// ═══════════════════════════════════════════════════════════════

/** Standard outline/border */
val ArchiveOutline = Color(0xFF242933)

/** Divider line */
val ArchiveDivider = Color(0xFF1A1F28)

/** Focus outline (uses impossible accent) */
val ArchiveFocusOutline = ImpossibleAccent

// ═══════════════════════════════════════════════════════════════
//  Semantic Colors (Status, Alerts)
// ═══════════════════════════════════════════════════════════════

val ArchiveSuccess = Color(0xFF4ADE80)
val ArchiveWarning = Color(0xFFFBBF24)
val ArchiveError = Color(0xFFF87171)
val ArchiveInfo = Color(0xFF60A5FA)

// ═══════════════════════════════════════════════════════════════
//  Stone Texture Tones (for surface composables)
// ═══════════════════════════════════════════════════════════════

/** Slab surface - matte, low visual noise */
val StoneSlab = Color(0xFF12161E)

/** Relic surface - slightly elevated, aged aesthetic */
val StoneRelic = Color(0xFF181D26)

/** Ash - for filament dividers */
val StoneAsh = Color(0xFF2A2F38)
