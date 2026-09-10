package com.ninelivesaudio.app.service.download

import com.ninelivesaudio.app.data.remote.dto.ApiAudioFile
import com.ninelivesaudio.app.data.remote.dto.ApiFileMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The expanded item response carries its own track order. The download engine
 * sorts by index before naming and downloading, so the mapping has to keep the
 * server's index instead of overwriting it with response position.
 */
class DownloadEngineMappingTest {

    private fun apiAudioFile(index: Int?, filename: String) = ApiAudioFile(
        ino = "ino-$filename",
        index = index,
        duration = 1.0,
        metadata = ApiFileMetadata(filename = filename, size = 10),
    )

    @Test
    fun `server reported indices survive the mapping`() {
        val mapped = toDomainAudioFiles(
            listOf(apiAudioFile(index = 2, filename = "b.mp3"), apiAudioFile(index = 1, filename = "a.mp3")),
        )

        assertEquals(listOf(2, 1), mapped.map { it.index })
    }

    @Test
    fun `a missing index falls back to response position`() {
        val mapped = toDomainAudioFiles(
            listOf(apiAudioFile(index = null, filename = "a.mp3"), apiAudioFile(index = null, filename = "b.mp3")),
        )

        assertEquals(listOf(0, 1), mapped.map { it.index })
    }

    @Test
    fun `filenames and sizes come across intact`() {
        val mapped = toDomainAudioFiles(listOf(apiAudioFile(index = 0, filename = "a.mp3")))

        assertEquals("a.mp3", mapped.single().filename)
        assertEquals(10L, mapped.single().size)
    }
}
