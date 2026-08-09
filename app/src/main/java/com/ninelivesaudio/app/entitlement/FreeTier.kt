package com.ninelivesaudio.app.entitlement

import com.ninelivesaudio.app.domain.model.ThemeMode
import com.ninelivesaudio.app.ui.library.SortMode
import com.ninelivesaudio.app.ui.library.ViewMode

/**
 * What the free tier can reach, declared in one place.
 *
 * Every gate in the UI asks this rather than carrying its own list. Free-tier
 * membership scattered across a dozen `when` branches is how a feature ends up
 * free on one screen and locked on another, and how the store listing ends up
 * describing a tier that does not exist.
 *
 * Pure and exhaustively tested, like the rest of this package.
 */
object FreeTier {

    /**
     * Sorts a free install can choose.
     *
     * `RECENTLY_PLAYED` is in here for a specific reason: it is the app's default
     * sort, in both `UiState` and `resetFilters()`. Keeping it free means the
     * default experience needs no entitlement fork at all, and a free user never
     * opens the Library to find it sorted in a way they cannot restore.
     *
     * The other two cover the spec's "recent plus title" free tier.
     */
    val SORT_MODES: Set<SortMode> = setOf(
        SortMode.RECENTLY_ADDED,
        SortMode.RECENTLY_PLAYED,
        SortMode.TITLE_AZ,
    )

    /** Grouping is an unlock feature. Free browses the flat list. */
    val VIEW_MODES: Set<ViewMode> = setOf(ViewMode.ALL)

    /**
     * The vault is what you get. Lighting is a privilege you buy.
     *
     * Also the app's default, so a free install never sees a theme it cannot
     * choose again.
     */
    val THEME: ThemeMode = ThemeMode.NOIR

    fun allowsSort(sort: SortMode, isUnlocked: Boolean): Boolean =
        isUnlocked || sort in SORT_MODES

    fun allowsViewMode(mode: ViewMode, isUnlocked: Boolean): Boolean =
        isUnlocked || mode in VIEW_MODES

    fun allowsTheme(theme: ThemeMode, isUnlocked: Boolean): Boolean =
        isUnlocked || theme == THEME

    /**
     * The sort actually applied, clamped by entitlement.
     *
     * Gating the control is not enough on its own. Someone who picked "Longest
     * first" while unlocked, then lost entitlement, would otherwise keep a
     * premium sort running behind a greyed chip, which is the same
     * engine-disagrees-with-UI failure the settings normalization layer exists
     * to prevent.
     *
     * Falls back to the app's own default, not to the first free entry, so a
     * downgrade lands somewhere the user recognises.
     */
    fun effectiveSort(stored: SortMode, isUnlocked: Boolean): SortMode =
        if (allowsSort(stored, isUnlocked)) stored else SortMode.RECENTLY_PLAYED

    /**
     * The only sleep-timer duration a free install can set, in minutes.
     *
     * Matches the store listing exactly, which promises "a 30-minute sleep
     * timer" and nothing else. If this number and that sentence ever disagree,
     * the sentence is what people paid attention to.
     */
    const val SLEEP_TIMER_MINUTES = 30

    /**
     * Whether a sleep-timer option is selectable.
     *
     * null means "Off" and is always allowed. Being unable to CANCEL a timer
     * would be a trap, not a paywall.
     */
    fun allowsSleepTimer(minutes: Int?, isUnlocked: Boolean): Boolean =
        isUnlocked || minutes == null || minutes == SLEEP_TIMER_MINUTES

    /** Whether a playback speed is selectable. Free is normal speed only. */
    fun allowsSpeed(speed: Float, isUnlocked: Boolean): Boolean =
        isUnlocked || speed == EffectiveSettings.FREE_SPEED.toFloat()

    /** The grouping actually applied, clamped by entitlement. */
    fun effectiveViewMode(stored: ViewMode, isUnlocked: Boolean): ViewMode =
        if (allowsViewMode(stored, isUnlocked)) stored else ViewMode.ALL
}
