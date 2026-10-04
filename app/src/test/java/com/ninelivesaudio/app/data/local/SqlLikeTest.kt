package com.ninelivesaudio.app.data.local

import com.ninelivesaudio.app.data.repository.buildLibrarySql
import com.ninelivesaudio.app.data.repository.buildLibrarySqlArgs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** "%" and "_" typed in the Library search match themselves, not anything. */
class SqlLikeTest {

    @Test
    fun `wildcards and the escape character are escaped`() {
        assertEquals("100\\%", escapeLike("100%"))
        assertEquals("a\\_b", escapeLike("a_b"))
        assertEquals("C:\\\\books", escapeLike("C:\\books"))
        assertEquals("plain words", escapeLike("plain words"))
        assertEquals("%\\%\\_%", containsLikePattern("%_"))
    }

    @Test
    fun `every searched column declares the escape character`() {
        val sql = buildLibrarySql(tab = 0, hideFinished = false, downloadedOnly = false, hasSearch = true)
        val likes = Regex("LIKE \\?").findAll(sql).count()
        val escaped = Regex("LIKE \\? ESCAPE '\\\\'").findAll(sql).count()
        assertEquals(4, likes)
        assertEquals(likes, escaped)
    }

    @Test
    fun `the search binds escaped patterns, one per column`() {
        val pattern = "%50\\% off\\_sale%"
        assertArrayEquals(
            arrayOf<Any>("lib", pattern, pattern, pattern, pattern),
            buildLibrarySqlArgs("lib", "50% off_sale"),
        )
        assertArrayEquals(arrayOf<Any>("lib"), buildLibrarySqlArgs("lib", "  "))
    }
}
