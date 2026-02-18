package com.ninelivesaudio.app.ui.copy.unhinged.catalog

import com.ninelivesaudio.app.domain.model.AudioBook
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Book Whisper Catalog — Time-Aware Atmospheric Phrases
 *
 * Whispers that shift based on how much of a book has been listened to.
 * Two override tiers can supersede the time-based tier:
 *   1. **Last 10%** — when ≥90% progress and NOT finished
 *   2. **Finished** — when the book is marked complete
 *
 * Selection is deterministic per book (seeded by book ID) so the same book
 * always shows the same whisper in a given session.
 */
object BookWhisperCatalog {

    // ═══════════════════════════════════════════════════════════════
    //  0–5 Minutes Listened
    // ═══════════════════════════════════════════════════════════════

    private val TIER_0_5_MIN = listOf(
        "It hasn't noticed you yet.",
        "The first minutes are always polite.",
        "You're still at the threshold.",
        "The door hasn't sealed.",
        "Nothing has followed you. Yet.",
        "The shelves are pretending.",
        "It's quiet on purpose.",
        "You can still leave clean.",
        "The story is stretching.",
        "The ink is still wet.",
        "The cover feels warm.",
        "This is the safe part.",
        "It hasn't memorized you.",
        "You're just browsing. Probably.",
        "The narrator hasn't leaned in.",
        "The silence is rehearsed.",
        "It hasn't adjusted to you.",
        "The air hasn't shifted.",
        "The shelf is patient.",
        "You're not committed.",
        "The page hasn't turned you yet.",
        "It doesn't know your pace.",
        "The binding hasn't tightened.",
        "The lights are steady.",
        "You're not marked.",
        "The Library is observing quietly.",
        "The title looks harmless.",
        "Nothing has whispered back.",
        "You're still outside the story.",
        "The margins are empty.",
        "The dust hasn't moved.",
        "The catalog is calm.",
        "This is only the beginning.",
        "The spines are aligned.",
        "It's behaving normally.",
        "The words are cooperative.",
        "The shadows are shallow.",
        "You're just visiting.",
        "No one has counted you.",
        "It hasn't chosen you.",
    )

    // ═══════════════════════════════════════════════════════════════
    //  6–60 Minutes Listened
    // ═══════════════════════════════════════════════════════════════

    private val TIER_6_60_MIN = listOf(
        "You've crossed the threshold.",
        "It recognizes your pauses.",
        "The shelf shifted slightly.",
        "You're leaving a trace.",
        "The story has adjusted to you.",
        "It's listening back.",
        "The margins aren't empty anymore.",
        "You've been noticed.",
        "The air is different now.",
        "It remembers you.",
    )

    // ═══════════════════════════════════════════════════════════════
    //  61–180 Minutes Listened
    // ═══════════════════════════════════════════════════════════════

    private val TIER_61_180_MIN = listOf(
        "You've lingered too long.",
        "The narrator sounds closer.",
        "It knows where you rewind.",
        "You're being cataloged.",
        "The story has your rhythm.",
        "The silence feels heavier.",
        "Something here is counting.",
        "You're not just browsing anymore.",
        "The shelves lean inward.",
        "It expects you now.",
    )

    // ═══════════════════════════════════════════════════════════════
    //  3–6 Hours Listened
    // ═══════════════════════════════════════════════════════════════

    private val TIER_3_6_HR = listOf(
        "You've fed it attention.",
        "It waits when you pause.",
        "Your listening has weight.",
        "The Library approves quietly.",
        "It has learned your pace.",
        "You're predictable now.",
        "The story feels personal.",
        "The narrator breathes with you.",
        "You've worn a groove in it.",
        "It's becoming familiar with you.",
    )

    // ═══════════════════════════════════════════════════════════════
    //  6–9 Hours Listened
    // ═══════════════════════════════════════════════════════════════

    private val TIER_6_9_HR = listOf(
        "You return like a ritual.",
        "It anticipates your arrival.",
        "The story fits too well.",
        "You're part of its routine.",
        "It has memorized your silence.",
        "The margins know your name.",
        "You keep coming back hungry.",
        "It feels less like a book.",
        "Your attention belongs here.",
        "You're being filed carefully.",
    )

    // ═══════════════════════════════════════════════════════════════
    //  9–12 Hours Listened
    // ═══════════════════════════════════════════════════════════════

    private val TIER_9_12_HR = listOf(
        "You've given it hours.",
        "It has shaped around you.",
        "The silence after chapters lingers.",
        "You don't leave easily.",
        "It feels incomplete without you.",
        "The shelf keeps your outline.",
        "You've become consistent.",
        "The story trusts your return.",
        "You're not a visitor anymore.",
        "It sounds different in your hands.",
    )

    // ═══════════════════════════════════════════════════════════════
    //  12–20 Hours Listened
    // ═══════════════════════════════════════════════════════════════

    private val TIER_12_20_HR = listOf(
        "This one has claimed you.",
        "The Library recognizes devotion.",
        "You've worn through the surface.",
        "It's part of your pattern.",
        "You circle it like orbit.",
        "It has learned your breathing.",
        "You've stayed longer than most.",
        "The story rests differently now.",
        "You've become necessary to it.",
        "It waits for you daily.",
    )

    // ═══════════════════════════════════════════════════════════════
    //  20–30 Hours Listened
    // ═══════════════════════════════════════════════════════════════

    private val TIER_20_30_HR = listOf(
        "You're deep in its spine.",
        "It has shaped your hours.",
        "The ending is approaching slowly.",
        "You've given it time to root.",
        "It doesn't release easily.",
        "You've invested something real.",
        "The Library is watching closely.",
        "You've almost seen too much.",
        "It has settled into you.",
        "The pages feel thinner now.",
    )

    // ═══════════════════════════════════════════════════════════════
    //  30+ Hours Listened
    // ═══════════════════════════════════════════════════════════════

    private val TIER_30_PLUS_HR = listOf(
        "You live here now.",
        "It feels unfinished without you.",
        "The Library marks you as recurring.",
        "You've changed with it.",
        "It won't forget you.",
        "You've crossed a quiet line.",
        "The story recognizes you instantly.",
        "You've given it enough to matter.",
        "You carry it differently now.",
        "You belong to this one.",
    )

    // ═══════════════════════════════════════════════════════════════
    //  Override: Last 10% Remaining (not finished)
    // ═══════════════════════════════════════════════════════════════

    private val OVERRIDE_LAST_10_PERCENT = listOf(
        "Ten percent left. You're stalling.",
        "It's right there. Finish it.",
        "You can feel the ending watching you.",
        "Why did you stop now?",
        "You're afraid of the last page.",
        "It knows you're hesitating.",
        "Just press play.",
        "The ending isn't going to soften.",
        "You've come this far. Coward.",
        "The final chapter is impatient.",
        "You're circling the conclusion.",
        "It's waiting for you to commit.",
        "Don't pretend you're busy.",
        "The last words are loaded.",
        "You're almost done. That's the problem.",
    )

    // ═══════════════════════════════════════════════════════════════
    //  Override: Finished
    // ═══════════════════════════════════════════════════════════════

    private val OVERRIDE_FINISHED = listOf(
        "Completed doesn't mean released.",
        "You closed it. It stayed open.",
        "The ending followed you out.",
        "It filed you under \"returning.\"",
        "The silence after was loud.",
        "You took something with you.",
        "It left something behind.",
        "Finished is just a label.",
        "The last chapter is still moving.",
        "It remembers how you ended it.",
        "You weren't the same at the end.",
        "The story adjusted you slightly.",
        "It settled into your history.",
        "The Library marked this as \"complete.\"",
        "You lingered after the credits.",
        "The ending wasn't the end.",
        "It fits differently now.",
        "You carry its weight quietly.",
        "It approved of your pace.",
        "Something changed at the final line.",
        "You'll hear it again later.",
        "It knows you finished it.",
        "You've been altered subtly.",
        "The silence has texture now.",
        "It closed more than pages.",
        "You absorbed more than words.",
        "It recognized your patience.",
        "The shelf feels lighter now.",
        "You left a mark on it.",
        "It expects your return.",
    )

    // ═══════════════════════════════════════════════════════════════
    //  Public API
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get a whisper for a book based on its listening state.
     *
     * Override priority:
     *   1. Finished → [OVERRIDE_FINISHED]
     *   2. ≥90% progress and not finished → [OVERRIDE_LAST_10_PERCENT]
     *   3. Otherwise → time-based tier from [currentTime]
     *
     * @param epoch Optional epoch counter that changes the seed so whispers
     *   re-roll each time the Library is entered. When 0 (default), behavior
     *   is the original deterministic-per-book selection.
     */
    fun getWhisper(book: AudioBook, epoch: Int = 0): String {
        val pool = getWhisperPool(book)
        val seed = book.id.hashCode().toLong() + epoch * 31L
        val index = Random(seed).nextInt(pool.size)
        return pool[index]
    }

    /**
     * Whether a book should show a whisper at all.
     *
     * Returns true for ~50% of books. The selection is deterministic for
     * a given (bookId, epoch) pair so the same books show/hide whispers
     * within a single Library visit, but the set re-rolls on each nav tap.
     */
    fun shouldShowWhisper(bookId: String, epoch: Int = 0): Boolean {
        val seed = bookId.hashCode().toLong() + epoch * 17L
        return Random(seed).nextBoolean()
    }

    /**
     * Get a whisper for the Player screen where we have separate
     * position/duration/finished data instead of an [AudioBook] object.
     */
    fun getWhisper(
        bookId: String,
        position: Duration,
        duration: Duration,
        isFinished: Boolean,
    ): String {
        val pool = getWhisperPool(position, duration, isFinished)
        val seed = bookId.hashCode().toLong()
        val index = Random(seed).nextInt(pool.size)
        return pool[index]
    }

    /**
     * Resolve which phrase pool applies for an [AudioBook].
     */
    private fun getWhisperPool(book: AudioBook): List<String> {
        return getWhisperPool(book.currentTime, book.duration, book.isFinished)
    }

    /**
     * Resolve which phrase pool applies for raw playback state.
     */
    private fun getWhisperPool(
        position: Duration,
        duration: Duration,
        isFinished: Boolean,
    ): List<String> {
        // Override 1: Finished
        if (isFinished) return OVERRIDE_FINISHED

        // Override 2: Last 10% remaining (progress ≥ 90%)
        if (duration > Duration.ZERO) {
            val progressFraction = position.inWholeMilliseconds.toDouble() /
                duration.inWholeMilliseconds.toDouble()
            if (progressFraction >= 0.90) return OVERRIDE_LAST_10_PERCENT
        }

        // Time-based tiers
        return getTimeTier(position)
    }

    /**
     * Select the time-based phrase pool from listening position.
     */
    private fun getTimeTier(listened: Duration): List<String> {
        return when {
            listened <= 5.minutes   -> TIER_0_5_MIN
            listened <= 60.minutes  -> TIER_6_60_MIN
            listened <= 180.minutes -> TIER_61_180_MIN
            listened <= 6.hours     -> TIER_3_6_HR
            listened <= 9.hours     -> TIER_6_9_HR
            listened <= 12.hours    -> TIER_9_12_HR
            listened <= 20.hours    -> TIER_12_20_HR
            listened <= 30.hours    -> TIER_20_30_HR
            else                    -> TIER_30_PLUS_HR
        }
    }
}
