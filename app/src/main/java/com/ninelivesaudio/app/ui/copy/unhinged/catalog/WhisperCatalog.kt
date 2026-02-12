package com.ninelivesaudio.app.ui.copy.unhinged.catalog

/**
 * Whisper Catalog — Contextual Atmospheric Messages
 *
 * Whispers are transient text snippets that appear at safe moments.
 * They display for ~4 seconds and fade out. Non-interactive.
 * One per session section maximum.
 *
 * **Tone**: Dungeon Crawler Carl meets John Dies at the End.
 * Odd, not edgy. Atmospheric, not tryhard.
 * The app knows something you don't, but it's not being smug about it.
 */

/**
 * Whisper contexts - when/where whispers can appear
 */
enum class WhisperContext {
    APP_OPENED,
    PLAYBACK_RESUMED,
    CHAPTER_FINISHED,
    DOWNLOAD_COMPLETED,
    BOOK_FINISHED,
    IDLE
}

/**
 * Whisper catalog - organized by context
 *
 * Each context has 5-10 possible whispers so they don't repeat quickly.
 * The system will select deterministically based on seed, so it feels curated.
 */
object WhisperCatalog {

    // ═══════════════════════════════════════════════════════════════
    //  App Opened
    // ═══════════════════════════════════════════════════════════════

    val APP_OPENED = listOf(
        "The archive recognizes your footsteps.",
        "Welcome back. The shelves didn't move. Much.",
        "You returned. The bookmark held.",
        "Everything is where you left it. Almost everything.",
        "The lights came on before you arrived.",
        "Session noted. Duration unknown.",
        "The front door was already open.",
        "Right where we left off. More or less."
    )

    // ═══════════════════════════════════════════════════════════════
    //  Playback Resumed
    // ═══════════════════════════════════════════════════════════════

    val PLAYBACK_RESUMED = listOf(
        "The telling continues.",
        "It was mid-sentence. It waited.",
        "Press play. The words were patient.",
        "Resuming from the last known position. The last *admitted* position.",
        "The story picks up. It never actually stopped.",
        "Back to it. The narrator cleared their throat.",
        "Chapter resumed. Time is approximate.",
        "Where were we. Right. There."
    )

    // ═══════════════════════════════════════════════════════════════
    //  Chapter Finished
    // ═══════════════════════════════════════════════════════════════

    val CHAPTER_FINISHED = listOf(
        "Something finished. Something else began.",
        "Chapter complete. The next one knows you're coming.",
        "That chapter is behind you now.",
        "One more down. The book is lighter. Metaphorically.",
        "Noted. Filed. Archived. Next.",
        "You made it through. Not everyone does. Metaphorically.",
        "Another chapter sealed. The binding holds.",
        "The page turned itself."
    )

    // ═══════════════════════════════════════════════════════════════
    //  Download Completed
    // ═══════════════════════════════════════════════════════════════

    val DOWNLOAD_COMPLETED = listOf(
        "Retrieved. Stored. Yours now.",
        "Download complete. The vault grows.",
        "Another artifact secured.",
        "It made it through. Intact, we think.",
        "Filed locally. The connection can rest.",
        "Stored in the vault. The shelves adjusted.",
        "Acquisition complete.",
        "It's here now. It's not going anywhere."
    )

    // ═══════════════════════════════════════════════════════════════
    //  Book Finished (entire book)
    // ═══════════════════════════════════════════════════════════════

    val BOOK_FINISHED = listOf(
        "The telling is complete. The silence that follows is part of it.",
        "Finished. The book closes. It may open differently next time.",
        "You reached the end. The archive notes your persistence.",
        "Complete. The spine relaxes.",
        "That one's done. Take a moment before the next.",
        "The last word has been spoken. For this one."
    )

    // ═══════════════════════════════════════════════════════════════
    //  Idle (Home screen, no action for 60+ seconds)
    // ═══════════════════════════════════════════════════════════════

    val IDLE = listOf(
        "Still here?",
        "The shelves are quiet tonight.",
        "Nothing's happening. That's fine. Things don't always have to happen.",
        "The archive is patient.",
        "Idle. The dust settles.",
        "Take your time. The books aren't going anywhere. Probably."
    )

    /**
     * Get whispers for a specific context
     */
    fun getWhispers(context: WhisperContext): List<String> {
        return when (context) {
            WhisperContext.APP_OPENED -> APP_OPENED
            WhisperContext.PLAYBACK_RESUMED -> PLAYBACK_RESUMED
            WhisperContext.CHAPTER_FINISHED -> CHAPTER_FINISHED
            WhisperContext.DOWNLOAD_COMPLETED -> DOWNLOAD_COMPLETED
            WhisperContext.BOOK_FINISHED -> BOOK_FINISHED
            WhisperContext.IDLE -> IDLE
        }
    }

    /**
     * Get a specific whisper by context and index (deterministic)
     */
    fun getWhisper(context: WhisperContext, index: Int): String {
        val whispers = getWhispers(context)
        return whispers[index % whispers.size]
    }
}
