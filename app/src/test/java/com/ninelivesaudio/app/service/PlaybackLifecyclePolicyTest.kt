package com.ninelivesaudio.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackLifecyclePolicyTest {

    @Test
    fun `paused restore does not open a listening session`() {
        assertEquals(
            ListeningSessionKind.NONE,
            listeningSessionToOpen(
                playbackStarting = false,
                isScannedLocalBook = true,
                hasServerSession = false,
                hasLocalSession = false,
            ),
        )
        assertEquals(
            ListeningSessionKind.NONE,
            listeningSessionToOpen(
                playbackStarting = false,
                isScannedLocalBook = false,
                hasServerSession = false,
                hasLocalSession = false,
            ),
        )
    }

    @Test
    fun `first playback opens the matching listening session once`() {
        assertEquals(
            ListeningSessionKind.LOCAL,
            listeningSessionToOpen(
                playbackStarting = true,
                isScannedLocalBook = true,
                hasServerSession = false,
                hasLocalSession = false,
            ),
        )
        assertEquals(
            ListeningSessionKind.SERVER,
            listeningSessionToOpen(
                playbackStarting = true,
                isScannedLocalBook = false,
                hasServerSession = false,
                hasLocalSession = false,
            ),
        )
        assertEquals(
            ListeningSessionKind.NONE,
            listeningSessionToOpen(
                playbackStarting = true,
                isScannedLocalBook = false,
                hasServerSession = true,
                hasLocalSession = false,
            ),
        )
    }

    @Test
    fun `natural completion clears the current item and replay saves it again`() {
        assertEquals(
            PlaybackItemPersistence.CLEAR,
            playbackItemPersistenceAction(naturalCompletion = true, playbackStarting = false),
        )
        assertEquals(
            PlaybackItemPersistence.SAVE,
            playbackItemPersistenceAction(naturalCompletion = false, playbackStarting = true),
        )
        assertEquals(
            PlaybackItemPersistence.KEEP,
            playbackItemPersistenceAction(naturalCompletion = false, playbackStarting = false),
        )
    }
}
