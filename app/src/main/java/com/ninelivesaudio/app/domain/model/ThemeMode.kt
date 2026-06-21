package com.ninelivesaudio.app.domain.model

import kotlinx.serialization.Serializable

/**
 * Selectable app color themes.
 *
 * NOIR is the default Archive Beneath look (deep indigo void with gold filament
 * accent) and must remain the default so existing users see no change unless they
 * choose otherwise. The other three are user-selectable alternates.
 *
 * Persisted by name inside [AppSettings] via kotlinx.serialization. New values
 * decode safely because the field carries a default and the settings Json is
 * configured with ignoreUnknownKeys.
 */
@Serializable
enum class ThemeMode {
    /** Default dark Archive Beneath palette (deep indigo void, gold filament). */
    NOIR,

    /** Genuine light theme with good daylight contrast, gold accent preserved. */
    BRIGHT,

    /** True-black (#000000) dark theme for OLED battery savings. */
    AMOLED,

    /** Warm candlelit sepia variant. Same gold identity, warmer browns. */
    CANDLELIGHT,
}
