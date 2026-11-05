package com.ninelivesaudio.app.service

import androidx.media3.common.Player
import com.ninelivesaudio.app.domain.model.Chapter
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterAwareForwardingPlayerSeekTest {

    @Test
    fun `Auto seek buttons delegate to book-aware skip handlers`() {
        val player = ChapterAwareForwardingPlayer(noOpPlayer())
        val events = mutableListOf<String>()
        player.seekBackHandler = { events += "back" }
        player.seekForwardHandler = { events += "forward" }

        player.seekBack()
        player.seekForward()

        assertEquals(listOf("back", "forward"), events)
    }

    // A finished book reopens parked at its end (Jeff decided 2026-11-02).
    // The player waits at 0:00, so a book without chapters must still show
    // the end to the notification and Android Auto.
    @Test
    fun `a parked book without chapters shows its end, not the player's 0 00`() {
        val player = ChapterAwareForwardingPlayer(singleTrackPlayer(durationMs = 3_600_000L, positionMs = 0L))
        assertEquals(0L, player.currentPosition)
        player.parkedAtEnd = true
        assertEquals(3_600_000L, player.currentPosition)
        assertEquals(3_600_000L, player.contentPosition)
    }

    @Test
    fun `a parked book with chapters shows the end of its last chapter`() {
        val player = ChapterAwareForwardingPlayer(singleTrackPlayer(durationMs = 3_600_000L, positionMs = 0L))
        val last = Chapter(id = 1, start = 1_800.0, end = 3_600.0)
        player.chapters = listOf(Chapter(id = 0, start = 0.0, end = 1_800.0), last)
        player.currentChapter = last
        player.currentChapterIndex = 1
        player.absolutePositionMs = 3_600_000L
        player.parkedAtEnd = true
        assertEquals(1_800_000L, player.currentPosition)
        assertEquals(1_800_000L, player.duration)
    }

    @Test
    fun `a controller seek on a parked book without chapters goes through the book's seek`() {
        // Straight to the player, a seek to the end would end the book again.
        val player = ChapterAwareForwardingPlayer(singleTrackPlayer(durationMs = 3_600_000L, positionMs = 0L))
        val seeks = mutableListOf<Long>()
        player.seekHandler = { seeks += it }
        player.parkedAtEnd = true
        player.seekTo(3_600_000L)
        assertEquals(listOf(3_600_000L), seeks)
    }

    @Test
    fun `an indexed controller seek on a parked book without chapters goes through the book's seek`() {
        // Straight to the player, a seek into the last track's end would end the book again.
        val player = ChapterAwareForwardingPlayer(singleTrackPlayer(durationMs = 3_600_000L, positionMs = 0L))
        val seeks = mutableListOf<Pair<Int, Long>>()
        player.indexedSeekHandler = { index, ms -> seeks += index to ms }
        player.parkedAtEnd = true
        player.seekTo(2, 90_000L)
        assertEquals(listOf(2 to 90_000L), seeks)
    }

    private fun singleTrackPlayer(durationMs: Long, positionMs: Long): Player = Proxy.newProxyInstance(
        Player::class.java.classLoader,
        arrayOf(Player::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getDuration", "getContentDuration" -> durationMs
            "getCurrentPosition", "getContentPosition" -> positionMs
            "getMediaItemCount" -> 1
            else -> when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                java.lang.Double.TYPE -> 0.0
                else -> null
            }
        }
    } as Player

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
