package com.ninelivesaudio.app.data.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ScopeCheckedCatalogDeleteTest {
    @Test
    fun `scope flip after catalog ID read prevents delete`() = runBlocking {
        var current = true
        val deleted = mutableListOf<String>()

        val applied = deleteCatalogIdsIfCurrent(
            readIds = {
                current = false
                listOf("nlr1:owner:book")
            },
            isCurrentScope = { current },
            deleteIds = { deleted += it },
        )

        assertEquals(false, applied)
        assertEquals(emptyList<String>(), deleted)
    }

    @Test
    fun `scope still current after catalog ID read deletes only enumerated IDs`() = runBlocking {
        val deleted = mutableListOf<String>()

        val applied = deleteCatalogIdsIfCurrent(
            readIds = { listOf("nlr1:owner:book") },
            isCurrentScope = { true },
            deleteIds = { deleted += it },
        )

        assertEquals(true, applied)
        assertEquals(listOf("nlr1:owner:book"), deleted)
    }
}
