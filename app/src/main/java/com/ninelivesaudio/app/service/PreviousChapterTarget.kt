package com.ninelivesaudio.app.service

/**
 * How far into a chapter Previous restarts it instead of going back one.
 * Matches the usual music and audiobook player feel.
 */
const val RESTART_CHAPTER_THRESHOLD_MS = 3_000L

/**
 * Which chapter a Previous press should land on (at its start).
 *
 * More than [RESTART_CHAPTER_THRESHOLD_MS] into a chapter restarts it.
 * Inside that window it goes to the chapter before, and the first chapter
 * just restarts. Shared by the in app button and the MediaSession (Android
 * Auto, notification, lock screen) so the two cannot drift apart.
 *
 * Returns -1 when there are no chapters.
 */
fun previousChapterTarget(currentIndex: Int, chapterCount: Int, positionInChapterMs: Long): Int {
    if (chapterCount <= 0) return -1
    if (currentIndex !in 0 until chapterCount) return 0
    if (positionInChapterMs > RESTART_CHAPTER_THRESHOLD_MS) return currentIndex
    return (currentIndex - 1).coerceAtLeast(0)
}
