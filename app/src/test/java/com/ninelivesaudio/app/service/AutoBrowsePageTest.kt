package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.data.local.entity.AutoBrowseRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android Auto's Library and Downloaded rows are light SQL rows, not whole
 * books. These pin how one becomes an Auto row. Paging lives in the tree
 * planner now (AutoBrowseFoldersTest, `the leaf window keeps the old paging
 * rules`).
 */
class AutoBrowsePageTest {

    @Test
    fun `a light row keeps the first genre and its covers`() {
        val item = AutoBrowseRow(
            id = "x",
            title = "Title",
            author = null,
            narrator = "Reader",
            coverPath = "https://abs.test/api/items/x/cover",
            localCoverPath = "file:///covers/x.jpg",
            genresJson = """["Mystery","Noir"]""",
        ).toAutoBookItem()

        assertEquals("Mystery", item.genre)
        assertEquals("", item.author)
        assertEquals("file:///covers/x.jpg", item.effectiveCoverPath)
    }

    @Test
    fun `broken or empty genres read as none`() {
        assertNull(AutoBrowseRow(id = "x", title = "T", genresJson = "not json").toAutoBookItem().genre)
        assertNull(AutoBrowseRow(id = "x", title = "T", genresJson = "[]").toAutoBookItem().genre)
        assertTrue(AutoBrowseRow(id = "x", title = "T").toAutoBookItem().genre == null)
    }
}
