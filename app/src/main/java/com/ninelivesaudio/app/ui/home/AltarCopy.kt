package com.ninelivesaudio.app.ui.home

/**
 * Altar Copy
 *
 * Generates increasingly unhinged phrases based on total listening time.
 * Deterministic selection so it doesn't flicker every recomposition.
 */
internal object AltarCopy {

    /**
     * @param totalListeningSeconds Total seconds listened across all books.
     */
    fun phrase(totalListeningSeconds: Double): String {
        val minutes = (totalListeningSeconds / 60.0).coerceAtLeast(0.0)
        val hours = minutes / 60.0

        val tier = when {
            hours < 0.5 -> 0
            hours < 2.0 -> 1
            hours < 8.0 -> 2
            hours < 24.0 -> 3
            hours < 60.0 -> 4
            hours < 150.0 -> 5
            else -> 6
        }

        val options = when (tier) {
            0 -> listOf(
                "The altar is cold. Offer a minute.",
                "No stain on the hourglass yet.",
                "A quiet vault. It can be louder.",
            )
            1 -> listOf(
                "You have begun feeding the shelves.",
                "The Archive has noticed your footsteps.",
                "A small offering. A real one.",
            )
            2 -> listOf(
                "The books hum when you pass.",
                "The altar warms. Something purrs under it.",
                "You are building a second memory. Carefully.",
            )
            3 -> listOf(
                "Time has a taste now. Metallic. Familiar.",
                "The Archive is rearranging itself around you.",
                "Your attention is leaving fingerprints on reality.",
            )
            4 -> listOf(
                "You are no longer listening. You are participating.",
                "The altar is hungry in new directions.",
                "Your hours are tying knots in the dark.",
            )
            5 -> listOf(
                "Your time has become a currency the universe accepts.",
                "The shelves have started shelving you back.",
                "The altar is wearing your devotion like jewelry.",
            )
            else -> listOf(
                "You have crossed the polite boundary. Welcome.",
                "The Archive has stopped pretending it is inanimate.",
                "Your hours are a summoning circle with excellent pacing.",
                "You gave the altar enough time to learn your name.",
            )
        }

        // Deterministic pick: changes only when total minutes changes enough.
        val key = (minutes.toLong() / 7L).coerceAtLeast(0L)
        val idx = (key % options.size).toInt()
        return options[idx]
    }

    /**
     * A short "title" for the altar based on escalation.
     */
    fun epithet(totalListeningSeconds: Double): String {
        val hours = (totalListeningSeconds / 3600.0).coerceAtLeast(0.0)
        val titles = when {
            hours < 1 -> listOf("NOVICE", "WANDERER")
            hours < 8 -> listOf("ACOLYTE", "THREADKEEPER")
            hours < 24 -> listOf("OFFERING-BEARER", "CATALOGUER")
            hours < 60 -> listOf("VAULT-FRIEND", "RING-TENDER")
            hours < 150 -> listOf("ARCHIVE-BOUND", "CLOCK-EATER")
            else -> listOf("ALTAR-SOVEREIGN", "LIBRARIAN OF TEETH")
        }
        val idx = ((hours.toLong() / 3L) % titles.size).toInt()
        return titles[idx]
    }
}
