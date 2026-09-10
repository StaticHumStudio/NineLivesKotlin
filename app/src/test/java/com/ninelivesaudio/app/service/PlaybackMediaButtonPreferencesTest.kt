package com.ninelivesaudio.app.service

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackMediaButtonPreferencesTest {

    @Test
    fun `audiobook controls advertise time skips plus chapter navigation`() {
        val buttons = audiobookButtonSpecs()

        assertEquals(4, buttons.size)
        assertEquals(AUTO_SEEK_BACK_10, buttons[0].customAction)
        assertEquals(CommandButton.ICON_SKIP_BACK_10, buttons[0].icon)
        assertEquals(AUTO_SEEK_FORWARD_30, buttons[1].customAction)
        assertEquals(CommandButton.ICON_SKIP_FORWARD_30, buttons[1].icon)
        assertEquals(Player.COMMAND_SEEK_TO_PREVIOUS, buttons[2].playerCommand)
        assertEquals(CommandButton.ICON_PREVIOUS, buttons[2].icon)
        assertEquals(Player.COMMAND_SEEK_TO_NEXT, buttons[3].playerCommand)
        assertEquals(CommandButton.ICON_NEXT, buttons[3].icon)
    }

    @Test
    @OptIn(UnstableApi::class)
    fun `configured button slots stay inside Media3's declared set`() {
        val validSlots = setOf(
            CommandButton.SLOT_CENTRAL,
            CommandButton.SLOT_BACK,
            CommandButton.SLOT_FORWARD,
            CommandButton.SLOT_BACK_SECONDARY,
            CommandButton.SLOT_FORWARD_SECONDARY,
            CommandButton.SLOT_OVERFLOW,
        )

        assertTrue(audiobookButtonSpecs().flatMap { it.slots }.all(validSlots::contains))
    }

    @Test
    fun `custom Auto seek actions map to whole book offsets`() {
        assertEquals(-10, autoSeekSeconds(AUTO_SEEK_BACK_10))
        assertEquals(30, autoSeekSeconds(AUTO_SEEK_FORWARD_30))
        assertEquals(null, autoSeekSeconds("unknown"))
    }
}
