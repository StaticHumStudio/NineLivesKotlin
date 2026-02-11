package com.ninelivesaudio.app.ui.theme

import androidx.compose.ui.graphics.Color

// ═══════════════════════════════════════════════════════════════
//  NineLivesAudio Cosmic Dark Theme — Color Tokens
//  Ported from CosmicColors.xaml
// ═══════════════════════════════════════════════════════════════

// Void Palette — Backgrounds & Surfaces
val VoidDeep = Color(0xFF070A10) // Updated to match UI brief
val VoidBase = Color(0xFF0A0E1A)
val VoidSurface = Color(0xFF111827)
val VoidElevated = Color(0xFF1A2236)
val NavRailBackground = Color(0xFF05070C) // Left nav rail specific

// Sigil Gold — Accent & Interactive
val SigilGold = Color(0xFFC5A55A)
val SigilGoldBright = Color(0xFFD4AF37)
val SigilGoldDim = Color(0xFF8B7340)
val SigilGoldFaint = Color(0xFF3D3220)

// Starlight — Primary Text
val Starlight = Color(0xFFFFFFFF)
val StarlightDim = Color(0xFFE0E0E8)

// Mist — Secondary Text & Borders
val Mist = Color(0xFF9BA4B5)
val MistFaint = Color(0xFF6B7280)

// Semantic Aliases
val PageBackground = VoidDeep
val CardBackground = VoidSurface
val CardBackgroundElevated = VoidElevated
val PrimaryText = Starlight
val SecondaryText = StarlightDim
val TertiaryText = Mist
val PlaceholderText = MistFaint
val AccentColor = SigilGold
val AccentBright = SigilGoldBright
val DividerColor = VoidElevated
val EntryBackground = VoidSurface
val EntryBorder = VoidElevated

// Status Colors
val CosmicSuccess = Color(0xFF4ADE80)
val CosmicWarning = Color(0xFFFBBF24)
val CosmicError = Color(0xFFF87171)
val CosmicInfo = Color(0xFF60A5FA)

// Cosmic Energy Gradient Stops (for Nine Lives)
val CosmicEnergy1 = Color(0xFF6366F1) // Indigo (used for radial gradient)
val CosmicEnergy2 = Color(0xFF8B5CF6) // Violet
val GradientBlue = Color(0xFF3B82F6) // Blue for radial background gradient
val CosmicEnergy3 = Color(0xFFA78BFA) // Light Violet
val CosmicEnergy4 = Color(0xFFC084FC) // Purple
val CosmicEnergy5 = Color(0xFFD946EF) // Fuchsia
val CosmicEnergy6 = Color(0xFFEC4899) // Pink
val CosmicEnergy7 = Color(0xFFF43F5E) // Rose
val CosmicEnergy8 = Color(0xFFFB923C) // Orange
val CosmicEnergy9 = Color(0xFFFBBF24) // Amber

val CosmicEnergyColors = listOf(
    CosmicEnergy1, CosmicEnergy2, CosmicEnergy3,
    CosmicEnergy4, CosmicEnergy5, CosmicEnergy6,
    CosmicEnergy7, CosmicEnergy8, CosmicEnergy9
)

// Progress Bar Colors
val ProgressTrack = VoidElevated
val ProgressFill = SigilGold

// ═══════════════════════════════════════════════════════════════
//  UI Brief Design Tokens (Exact Spec Compliance)
// ═══════════════════════════════════════════════════════════════

// Design tokens from UI specification document
val AppBg = VoidDeep                      // #070A10
val RailBg = NavRailBackground            // #05070C
val CardBg = Color(0xFF111A2A)            // #111A2A (spec-exact card background)
val StrokeMuted = Color(0xFF263248)       // #263248 (card borders)
val TextPrimary = Color(0xFFE8ECF6)       // #E8ECF6 (primary text)
val TextMuted = Color(0xFF9AA6BA)         // #9AA6BA (secondary text)
val AccentGold = Color(0xFFC9A24A)        // #C9A24A (spec-exact gold)
val NebulaBlue = Color(0xFF2B5AA8)        // #2B5AA8 (gradient color)
val NebulaViolet = Color(0xFF6B3FA6)      // #6B3FA6 (gradient color)
val SuccessGreen = Color(0xFF2DD36F)      // #2DD36F (connected status)
