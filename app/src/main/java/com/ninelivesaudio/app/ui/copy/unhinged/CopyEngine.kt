package com.ninelivesaudio.app.ui.copy.unhinged

import androidx.compose.runtime.Composable
import com.ninelivesaudio.app.settings.unhinged.CopyMode
import com.ninelivesaudio.app.ui.components.unhinged.LocalUnhingedSettings

/**
 * Copy Engine — 3-Tier Copy System
 *
 * Resolves copy strings based on the active copy mode (Normal/Ritual/Unhinged).
 * Provides deterministic selection of copy variants to avoid noise.
 *
 * **The 3 Tiers**:
 * - **Normal**: Standard labels, no flavor
 * - **Ritual**: Literal labels + subtle flavor subtitles (3-8 words)
 * - **Unhinged**: Literal labels + weirder flavor (5-15 words), more personality
 *
 * **Critical Rules**:
 * - Destructive actions (delete, remove) are ALWAYS literal, no flavor ever
 * - Confirmation dialogs remain plain (optional flavor line may appear below)
 * - Normal mode is always the baseline and always available
 */
object CopyEngine {

    /**
     * Get the appropriate subtitle based on current copy mode
     *
     * @param ritualSubtitle The Ritual mode subtitle
     * @param unhingedSubtitle The Unhinged mode subtitle
     * @return Subtitle for current mode, or null in Normal mode
     */
    @Composable
    fun getSubtitle(
        ritualSubtitle: String?,
        unhingedSubtitle: String?
    ): String? {
        val copyMode = LocalUnhingedSettings.current.copyMode

        return when (copyMode) {
            CopyMode.Normal -> null
            CopyMode.Ritual -> ritualSubtitle
            CopyMode.Unhinged -> unhingedSubtitle
        }
    }

    /**
     * Get flavor text for empty states
     */
    @Composable
    fun getEmptyStateFlavor(
        ritualFlavor: String?,
        unhingedFlavor: String?
    ): String? {
        return getSubtitle(ritualFlavor, unhingedFlavor)
    }

    /**
     * Get search hint text based on copy mode
     */
    @Composable
    fun getSearchHint(
        normalHint: String,
        ritualHint: String,
        unhingedHint: String
    ): String {
        val copyMode = LocalUnhingedSettings.current.copyMode

        return when (copyMode) {
            CopyMode.Normal -> normalHint
            CopyMode.Ritual -> ritualHint
            CopyMode.Unhinged -> unhingedHint
        }
    }

    /**
     * Check if destructive action (no flavor allowed)
     *
     * Destructive actions are identified by keywords in their labels.
     */
    fun isDestructiveAction(label: String): Boolean {
        val destructiveKeywords = setOf(
            "delete",
            "remove",
            "cancel",
            "discard",
            "clear",
            "erase"
        )

        return destructiveKeywords.any { keyword ->
            label.lowercase().contains(keyword)
        }
    }

    /**
     * Screen-specific tone control
     *
     * Some screens can be weirder than others. This controls the maximum
     * weirdness level per screen.
     */
    enum class ScreenTone {
        /** Low-stakes browsing, safe to be atmospheric */
        HIGH,

        /** Browsing mode, flavor adds personality */
        MEDIUM,

        /** Active interaction, controls must be instant and clear */
        LOW,

        /** Zero flavor, zero distraction (transport controls) */
        NONE
    }

    /**
     * Get screen tone for a given screen
     */
    fun getScreenTone(screenName: String): ScreenTone {
        return when (screenName.lowercase()) {
            "home", "dashboard" -> ScreenTone.HIGH
            "library", "search" -> ScreenTone.HIGH
            "downloads" -> ScreenTone.MEDIUM
            "bookmarks" -> ScreenTone.MEDIUM
            "settings" -> ScreenTone.MEDIUM
            "player", "nowplaying" -> ScreenTone.LOW
            "playertransport" -> ScreenTone.NONE
            else -> ScreenTone.MEDIUM
        }
    }

    /**
     * Should show flavor text for this screen and copy mode?
     */
    @Composable
    fun shouldShowFlavor(
        screenName: String,
        isDestructive: Boolean = false
    ): Boolean {
        val copyMode = LocalUnhingedSettings.current.copyMode

        // Never show flavor in Normal mode
        if (copyMode == CopyMode.Normal) return false

        // Never show flavor for destructive actions
        if (isDestructive) return false

        // Check screen tone
        val tone = getScreenTone(screenName)
        return when (tone) {
            ScreenTone.NONE -> false
            ScreenTone.LOW -> copyMode == CopyMode.Unhinged // Only unhinged mode
            ScreenTone.MEDIUM, ScreenTone.HIGH -> true // Both ritual and unhinged
        }
    }
}

/**
 * Copy Triple — Holds all 3 tiers of copy for a single element
 */
data class CopyTriple(
    val normal: String,
    val ritual: String?,
    val unhinged: String?
) {
    /**
     * Get the appropriate copy for current mode
     */
    @Composable
    fun getCopy(): String {
        val copyMode = LocalUnhingedSettings.current.copyMode

        return when (copyMode) {
            CopyMode.Normal -> normal
            CopyMode.Ritual -> ritual ?: normal
            CopyMode.Unhinged -> unhinged ?: ritual ?: normal
        }
    }

    /**
     * Get the subtitle (flavor text) for current mode
     */
    @Composable
    fun getSubtitle(): String? {
        val copyMode = LocalUnhingedSettings.current.copyMode

        return when (copyMode) {
            CopyMode.Normal -> null
            CopyMode.Ritual -> ritual
            CopyMode.Unhinged -> unhinged
        }
    }
}

/**
 * Extension: Get copy mode name for display
 */
fun CopyMode.displayName(): String {
    return when (this) {
        CopyMode.Normal -> "Normal"
        CopyMode.Ritual -> "Ritual"
        CopyMode.Unhinged -> "Unhinged"
    }
}

/**
 * Extension: Get copy mode description
 */
fun CopyMode.description(): String {
    return when (this) {
        CopyMode.Normal -> "Standard labels, no flavor"
        CopyMode.Ritual -> "Measured strangeness"
        CopyMode.Unhinged -> "The archive speaks freely"
    }
}
