package com.ninelivesaudio.app.entitlement

import com.ninelivesaudio.app.domain.model.ThemeMode
import com.ninelivesaudio.app.ui.dossier.DossierPeriod
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
     * Sorts a free install can choose: all of them, as of 2026-08-15.
     *
     * Previously three (`RECENTLY_ADDED`, `RECENTLY_PLAYED`, `TITLE_AZ`) with
     * the other eight gated. Ungated for two reasons. Sorting is how a person
     * finds a book in their OWN library, which puts it on the access side of
     * the line the paywall is not supposed to cross, next to Archive Shelf
     * automatic restoration and permanent deletion. And the category king
     * ships folder sorting and search in its free Basic tier, so gating ours
     * was a visible way to be stingier than the app everyone compares us to.
     *
     * The gate mechanism below is deliberately retained rather than deleted.
     * It costs nothing, it keeps the downgrade path tested, and per the plan
     * doc's own framing on the dropped source caps, a lever that is not
     * currently pulled is not the same as a lever that no longer exists.
     */
    val SORT_MODES: Set<SortMode> = SortMode.entries.toSet()

    /** Grouping is an unlock feature. Free browses the flat list. */
    val VIEW_MODES: Set<ViewMode> = setOf(ViewMode.ALL)

    /**
     * Themes a free install can choose.
     *
     * NOIR is the app's default and the brand's own ground, so a free install
     * never lands on a theme it cannot pick again.
     *
     * BRIGHT is free for a reason that is not generosity. A light theme is an
     * access need rather than a taste: light-on-dark text halates badly for
     * readers with astigmatism, and a dark-only app is unreadable in direct
     * sun. The paywall gates conveniences and never gates access, and
     * readability is access. Changed 2026-08-15, superseding the plan doc's
     * "the vault is what you get, lighting is a privilege you buy" line.
     *
     * AMOLED and CANDLELIGHT stay gated. Both are optional character, and
     * neither is the only way to read the screen.
     */
    val THEMES: Set<ThemeMode> = setOf(ThemeMode.NOIR, ThemeMode.BRIGHT)

    /**
     * The app's default, and where a downgrade lands if the stored theme is
     * gated. Falls back to the default rather than the first free entry, for
     * the same reason [effectiveSort] does.
     */
    val DEFAULT_THEME: ThemeMode = ThemeMode.NOIR

    fun allowsSort(sort: SortMode, isUnlocked: Boolean): Boolean =
        isUnlocked || sort in SORT_MODES

    fun allowsViewMode(mode: ViewMode, isUnlocked: Boolean): Boolean =
        isUnlocked || mode in VIEW_MODES

    fun allowsTheme(theme: ThemeMode, isUnlocked: Boolean): Boolean =
        isUnlocked || theme in THEMES

    /**
     * The theme actually applied, clamped by entitlement.
     *
     * Every consumer goes through this rather than comparing against a single
     * constant. The old single-theme constant was read in two places, and a
     * set-valued free tier would have silently kept forcing NOIR in whichever
     * one got missed.
     */
    fun effectiveTheme(stored: ThemeMode, isUnlocked: Boolean): ThemeMode =
        if (allowsTheme(stored, isUnlocked)) stored else DEFAULT_THEME

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

    /**
     * Dossier windows a free install can look through.
     *
     * The Dossier used to be gated whole, and that was backwards. The share
     * card is the one mechanism in the marketing plan where users do the
     * distribution, and locking it meant only people who had already converted
     * could generate one. An acquisition asset behind the purchase it is
     * supposed to drive is a closed loop.
     *
     * So free gets the 30-day window (and therefore the share card), and the
     * longer retrospectives stay as the thing worth buying. Changed 2026-08-16
     * on Jeff's call, superseding the whole-feature gate.
     */
    val DOSSIER_PERIODS: Set<DossierPeriod> = setOf(DossierPeriod.THIRTY_DAYS)

    /**
     * The window a downgrade lands on. Also the app's own default, so the
     * fallback is somewhere the user already recognises.
     */
    val DEFAULT_DOSSIER_PERIOD: DossierPeriod = DossierPeriod.THIRTY_DAYS

    fun allowsDossierPeriod(period: DossierPeriod, isUnlocked: Boolean): Boolean =
        isUnlocked || period in DOSSIER_PERIODS

    /**
     * The window actually queried, clamped by entitlement.
     *
     * This is the one that matters. Greying the chips only changes what can be
     * tapped, and the selection survives a downgrade in ViewModel state, so
     * somebody who picked "1 Year" while unlocked would otherwise keep reading
     * a year of data behind a greyed chip. The cutoff has to be clamped where
     * it is computed, not where it is displayed... same failure the theme
     * constant had when it was read in two places.
     */
    fun effectiveDossierPeriod(stored: DossierPeriod, isUnlocked: Boolean): DossierPeriod =
        if (allowsDossierPeriod(stored, isUnlocked)) stored else DEFAULT_DOSSIER_PERIOD

    /** The grouping actually applied, clamped by entitlement. */
    fun effectiveViewMode(stored: ViewMode, isUnlocked: Boolean): ViewMode =
        if (allowsViewMode(stored, isUnlocked)) stored else ViewMode.ALL
}
