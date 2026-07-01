package com.ninelivesaudio.app.ui.dossier

import org.junit.Assert.assertEquals
import org.junit.Test

/** When the toggle is off, archived books drop out of the stats book set. */
class StatsArchiveFilterTest {

    private val all = setOf("a", "b", "c")
    private val archived = setOf("b")

    @Test
    fun `includes archived when toggle on`() {
        assertEquals(all, statsBookIds(all, archived, includeArchived = true))
    }

    @Test
    fun `excludes archived when toggle off`() {
        assertEquals(setOf("a", "c"), statsBookIds(all, archived, includeArchived = false))
    }
}
