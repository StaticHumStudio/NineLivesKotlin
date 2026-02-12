package com.ninelivesaudio.app.ui.theme.unhinged.surfaces

/**
 * Surface Language — Unhinged Mode Composables
 *
 * This package contains reusable surface composables that render differently
 * based on the active theme (Normal vs Unhinged) while maintaining identical layout.
 *
 * ## Available Surfaces
 *
 * ### StoneSlabCard
 * For list items, book cards, "Continue Listening" tiles.
 * - **Normal**: Standard Material card with elevation
 * - **Unhinged**: Low-elevation matte slab, calm and utilitarian
 *
 * ### RelicSurface
 * For primary content areas, feature panels, important sections.
 * - **Normal**: Standard surface container
 * - **Unhinged**: Elevated "relic" with rim highlight and subtle depth
 *
 * ### FilamentDivider
 * For horizontal dividers and section breaks.
 * - **Normal**: Standard Material divider line
 * - **Unhinged**: Hairline filament with optional glow effect
 *
 * ## Design Philosophy
 *
 * **Slab surfaces**: Calm, utilitarian, low visual noise
 * **Relic surfaces**: Feel old and important, but never busy
 * **Void**: Deep backgrounds - just the stage, never interactive
 *
 * ## Usage
 *
 * All surfaces automatically adapt based on `LocalUnhingedSettings.current.isUnhingedThemeActive`.
 * You don't need to manually check theme state - just use the composables.
 *
 * ```kotlin
 * @Composable
 * fun MyScreen() {
 *     // This card will automatically switch between normal and slab aesthetic
 *     StoneSlabCard(onClick = { /* ... */ }) {
 *         Text("Book Title")
 *         Text("Author Name")
 *     }
 *
 *     FilamentDivider()
 *
 *     RelicSurface {
 *         // Important content here
 *     }
 * }
 * ```
 *
 * ## Layout Guarantee
 *
 * All surfaces maintain **identical layout and spacing** in both themes.
 * The only differences are:
 * - Colors
 * - Elevation values
 * - Border styles
 * - Subtle visual effects (glows, gradients)
 *
 * This ensures theme switching never breaks layouts or causes visual jumps.
 */
