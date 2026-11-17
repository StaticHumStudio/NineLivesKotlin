package com.ninelivesaudio.app.service

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Issue #118: Play from the headset, the notification or Android Auto reaches
 * the media session's player, never PlaybackManager.play(). The session player
 * hands play and pause to PlaybackManager, so every Play gets the same check
 * of the server's spot before the book resumes.
 */
class ControllerPlayRoutingTest {

    private fun recordingPlayer(calls: MutableList<String>): Player = Proxy.newProxyInstance(
        Player::class.java.classLoader,
        arrayOf(Player::class.java),
    ) { _, method, args ->
        if (method.name in setOf("play", "pause", "setPlayWhenReady")) {
            calls += method.name + (args?.joinToString(prefix = "(", postfix = ")") ?: "")
        }
        when (method.returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            else -> null
        }
    } as Player

    @Test
    fun `a controller play goes to the app's play, not straight to the player`() {
        val wrapped = mutableListOf<String>()
        val handled = mutableListOf<String>()
        val player = ChapterAwareForwardingPlayer(recordingPlayer(wrapped))
        player.playHandler = { handled += "play" }
        player.pauseHandler = { handled += "pause" }

        player.play()
        player.playWhenReady = true
        player.pause()
        player.playWhenReady = false

        assertEquals(listOf("play", "play", "pause", "pause"), handled)
        assertEquals(emptyList<String>(), wrapped)
    }

    @Test
    fun `with no handler play and pause reach the player as before`() {
        val wrapped = mutableListOf<String>()
        val player = ChapterAwareForwardingPlayer(recordingPlayer(wrapped))

        player.play()
        player.pause()

        assertEquals(listOf("play", "pause"), wrapped)
    }
}
