package com.ninelivesaudio.app.service

import androidx.media3.common.Player
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
