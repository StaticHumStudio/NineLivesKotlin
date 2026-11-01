package com.ninelivesaudio.app.ui.home

/*
 * The altar card's numbers, shared by Home and the Nine Lives home screen
 * widget so the two always say the same thing.
 */

/** How many recent books Home shows as Lives, and sums into "Total time given". */
internal const val RECENT_LIVES_LIMIT = 9

/** Formats seconds as compact listening time: "< 1m", "45m", "3h 22m". */
internal fun formatListeningTime(totalSeconds: Double): String {
    val totalMinutes = totalSeconds / 60.0
    if (totalMinutes < 1) return "< 1m"
    val totalHours = totalSeconds / 3600.0
    if (totalHours < 1) return "${totalMinutes.toInt()}m"
    val hours = totalHours.toInt()
    val mins = ((totalSeconds % 3600) / 60).toInt()
    return "${hours}h ${mins}m"
}

/**
 * "Total time given": the saved positions of the recent Lives, added up.
 * This is Home's long standing math, not lifetime listening.
 */
internal fun totalTimeGivenSeconds(currentTimesSeconds: List<Double>): Double = currentTimesSeconds.sum()
