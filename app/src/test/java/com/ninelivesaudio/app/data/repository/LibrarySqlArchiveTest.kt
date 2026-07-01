package com.ninelivesaudio.app.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Live tabs hide archived books; the Archive tab shows only archived. */
class LibrarySqlArchiveTest {

    @Test
    fun `live All tab excludes archived`() {
        val sql = buildLibrarySql(tab = 0, hideFinished = false, downloadedOnly = false, hasSearch = false)
        assertTrue(sql.contains("ab.ArchivedAt IS NULL"))
        assertFalse(sql.contains("ab.ArchivedAt IS NOT NULL"))
    }

    @Test
    fun `archive tab shows only archived`() {
        val sql = buildLibrarySql(tab = 4, hideFinished = false, downloadedOnly = false, hasSearch = false)
        assertTrue(sql.contains("ab.ArchivedAt IS NOT NULL"))
        assertFalse(sql.contains("ab.ArchivedAt IS NULL"))
    }

    @Test
    fun `downloaded tab still excludes archived`() {
        val sql = buildLibrarySql(tab = 3, hideFinished = false, downloadedOnly = false, hasSearch = false)
        assertTrue(sql.contains("ab.IsDownloaded = 1"))
        assertTrue(sql.contains("ab.ArchivedAt IS NULL"))
    }
}
