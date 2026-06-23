package com.ninelivesaudio.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/** On rescan, archive exactly the existing local books that the scan no longer found. */
class IdsToArchiveTest {

    @Test
    fun `archives existing ids missing from the scan`() {
        val result = idsToArchive(existingLocalIds = listOf("a", "b", "c"), scannedIds = listOf("a", "c"))
        assertEquals(listOf("b"), result)
    }

    @Test
    fun `archives nothing when all existing ids were scanned`() {
        assertEquals(emptyList<String>(), idsToArchive(listOf("a", "b"), listOf("a", "b", "z")))
    }

    @Test
    fun `archives all existing ids when the scan is empty`() {
        assertEquals(listOf("a", "b"), idsToArchive(listOf("a", "b"), emptyList()))
    }
}
