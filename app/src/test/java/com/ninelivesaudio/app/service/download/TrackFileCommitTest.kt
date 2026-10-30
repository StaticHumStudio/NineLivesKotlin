package com.ninelivesaudio.app.service.download

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A finished track only appears on disk once its bytes are on record. A
 * download with no bytes on record then has no finished track anywhere, which
 * is how the folder pick tells a download that wrote into a folder from one
 * that only waits in the queue (#88).
 */
class TrackFileCommitTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `a finished track is on record before it appears on disk`() = runBlocking {
        val dir = tempFolder.newFolder("book")
        val part = File(dir, "01.mp3.part").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val final = File(dir, "01.mp3")
        var finalExistedWhenRecorded = true

        val committed = commitTrackFile(part, final) {
            finalExistedWhenRecorded = final.exists()
            true
        }

        assertTrue(committed)
        assertFalse("the track appeared before its bytes were on record", finalExistedWhenRecorded)
        assertArrayEquals(byteArrayOf(1, 2, 3), final.readBytes())
        assertFalse(part.exists())
    }

    @Test
    fun `a track whose row moved on never appears on disk`() = runBlocking {
        val dir = tempFolder.newFolder("book")
        val part = File(dir, "01.mp3.part").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val final = File(dir, "01.mp3")

        val committed = commitTrackFile(part, final) { false }

        assertFalse(committed)
        assertFalse(final.exists())
        assertFalse(part.exists())
    }
}
