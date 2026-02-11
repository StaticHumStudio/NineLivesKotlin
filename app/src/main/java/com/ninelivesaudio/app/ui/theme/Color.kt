package com.ninelivesaudio.app.ui.theme

import androidx.compose.ui.graphics.Color

// ═══════════════════════════════════════════════════════════════
//  NineLivesAudio Cosmic Dark Theme — Color Tokens
//  Ported from CosmicColors.xaml
// ═══════════════════════════════════════════════════════════════

// Void Palette — Backgrounds & Surfaces
val VoidDeep = Color(0xFF050810)
val VoidBase = Color(0xFF0A0E1A)
val VoidSurface = Color(0xFF111827)
val VoidElevated = Color(0xFF1A2236)

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
val CosmicEnergy1 = Color(0xFF6366F1) // Indigo
val CosmicEnergy2 = Color(0xFF8B5CF6) // Violet
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
