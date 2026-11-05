package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.domain.model.Chapter
import androidx.media3.common.Player
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviousChapterTargetTest {

    @Test
    fun `well into a chapter, previous restarts that chapter`() {
        assertEquals(2, previousChapterTarget(currentIndex = 2, chapterCount = 5, positionInChapterMs = 60_000L))
    }

    @Test
    fun `just past the threshold still restarts the chapter`() {
        assertEquals(2, previousChapterTarget(2, 5, RESTART_CHAPTER_THRESHOLD_MS + 1))
    }

    @Test
    fun `inside the threshold, previous goes to the chapter before`() {
        assertEquals(1, previousChapterTarget(2, 5, 1_000L))
        assertEquals(1, previousChapterTarget(2, 5, RESTART_CHAPTER_THRESHOLD_MS))
    }

    @Test
    fun `first chapter always lands on its own start`() {
        assertEquals(0, previousChapterTarget(0, 5, 0L))
        assertEquals(0, previousChapterTarget(0, 5, 600_000L))
    }

    @Test
    fun `unknown current chapter lands on the first chapter`() {
        assertEquals(0, previousChapterTarget(-1, 5, 10_000L))
    }

    @Test
    fun `no chapters means no target`() {
        assertEquals(-1, previousChapterTarget(0, 0, 10_000L))
    }

    @Test
    fun `Auto and notification previous use the same rule`() {
        val chapters = listOf(
            Chapter(id = 0, start = 0.0, end = 100.0),
            Chapter(id = 1, start = 100.0, end = 200.0),
            Chapter(id = 2, start = 200.0, end = 300.0),
        )
        val seeks = mutableListOf<Long>()
        val player = ChapterAwareForwardingPlayer(noOpPlayer()).apply {
            this.chapters = chapters
            currentChapter = chapters[2]
            currentChapterIndex = 2
            seekHandler = { seeks += it }
        }

        player.absolutePositionMs = 250_000L // 50s into chapter 3
        player.seekToPrevious()
        player.absolutePositionMs = 201_000L // 1s into chapter 3
        player.seekToPrevious()

        assertEquals(listOf(200_000L, 100_000L), seeks)
    }

    @Test
    fun `MediaSession previous goes through PlaybackManager when wired`() {
        val calls = mutableListOf<String>()
        val player = ChapterAwareForwardingPlayer(noOpPlayer()).apply {
            chapters = listOf(Chapter(id = 0, start = 0.0, end = 100.0))
            currentChapter = chapters[0]
            currentChapterIndex = 0
            seekHandler = { calls += "seek" }
            previousChapterHandler = { calls += "manager" }
        }

        player.seekToPrevious()

        assertEquals(listOf("manager"), calls)
    }

    @Test
    fun `a book without chapters keeps the plain player previous`() {
        val calls = mutableListOf<String>()
        val player = ChapterAwareForwardingPlayer(noOpPlayer()).apply {
            previousChapterHandler = { calls += "manager" }
        }

        player.seekToPrevious()

        assertEquals(emptyList<String>(), calls)
    }

    private fun noOpPlayer(): Player = Proxy.newProxyInstance(
        Player::class.java.classLoader,
        arrayOf(Player::class.java),
    ) { _, method, _ ->
        when (method.returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            else -> null
        }
    } as Player
}
