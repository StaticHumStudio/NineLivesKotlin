package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.ui.library.letterLabelFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Android Auto folder ids are plain strings a car hands back later, maybe
 * after the library changed, so they must round trip exactly and anything
 * else must parse to nothing. Titles file under the same letter the Library
 * rail shows on the phone.
 */
class AutoFolderIdTest {

    private val letters = listOf("#") + ('A'..'Z').map { it.toString() }

    @Test
    fun `folder ids are stable strings`() {
        assertEquals("library", AutoFolderId(AutoBranch.LIBRARY, null).mediaId)
        assertEquals("library/T", AutoFolderId(AutoBranch.LIBRARY, "T").mediaId)
        assertEquals("library/TH", AutoFolderId(AutoBranch.LIBRARY, "TH").mediaId)
        assertEquals("library/TH/2", AutoFolderId(AutoBranch.LIBRARY, "TH", part = 2).mediaId)
        assertEquals("downloaded/#/3", AutoFolderId(AutoBranch.DOWNLOADED, "#", part = 3).mediaId)
        assertEquals("downloaded", AutoFolderId(AutoBranch.DOWNLOADED, null).mediaId)
    }

    @Test
    fun `every folder id parses back to itself`() {
        var checked = 0
        for (branch in AutoBranch.values()) {
            val ids = mutableListOf(AutoFolderId(branch, null))
            for (letter in letters) {
                ids += AutoFolderId(branch, letter)
                if (letter == "#") {
                    (1..3).forEach { ids += AutoFolderId(branch, "#", part = it) }
                    continue
                }
                for (second in letters) {
                    val pair = letter + second
                    ids += AutoFolderId(branch, pair)
                    (1..3).forEach { ids += AutoFolderId(branch, pair, part = it) }
                }
            }
            ids.forEach { id ->
                assertEquals(id.mediaId, id, AutoFolderId.parse(id.mediaId))
                checked++
            }
        }
        // 2 branches x (root, 27 letters, 3 number sign parts, 26 x 27 pairs x 4)
        assertEquals(2 * (1 + 27 + 3 + 26 * 27 * 4), checked)
    }

    @Test
    fun `anything else parses to null`() {
        listOf(
            "", "root", "recently_played", "book_abc", "library/", "library/t", "library/ABC", "library/#A",
            "library/T/2", "library/TH/0", "library/TH/01", "library/TH/x", "library/TH/2/9",
            "podcasts/T", "library/TH/99999999999", "library//", "library/TH/", "library/TH/-1",
            "library/TH/100001", "downloaded/##", "library/Th", "library/T /2", "setup_required",
        ).forEach { assertNull(it, AutoFolderId.parse(it)) }
    }

    @Test
    fun `a title files under the same letter the Library rail uses`() {
        listOf(
            "dune", "Dune", "The Hobbit", "A Wizard of Earthsea", "An Echo", "  Mort", "Émile", "Östen",
            "ñandú", "Øystein", "Łódź", "Æsir", "1984", "'Salem's Lot", "[REC]", "日本語", "Привет",
            "", "   ", " Mort", "Þorn", "ßtraße",
        ).forEach { assertEquals(it, letterLabelFor(it), autoTitleKey(it, 1)) }
    }

    @Test
    fun `articles are not skipped, matching the rail`() {
        assertEquals("T", autoTitleKey("The Hobbit", 1))
        assertEquals("TH", autoTitleKey("The Hobbit", 2))
        assertEquals("A", autoTitleKey("A Wizard of Earthsea", 1))
        assertEquals("AW", autoTitleKey("A Wizard of Earthsea", 2))
    }

    @Test
    fun `digits symbols and other alphabets land in the number sign`() {
        listOf("1984", "'Salem's Lot", "[REC]", "日本語", "Привет", "", "   ").forEach {
            assertEquals(it, "#", autoTitleKey(it, 1))
            assertEquals(it, "#", autoTitleKey(it, 2))
        }
    }

    @Test
    fun `the second letter skips spaces and folds accents`() {
        assertEquals("TO", autoTitleKey("Tō", 2))
        assertEquals("TR", autoTitleKey("T Rex", 2))
        assertEquals("AE", autoTitleKey("Æsir", 2))
        assertEquals("T#", autoTitleKey("T-Rex", 2))
        assertEquals("T#", autoTitleKey("T", 2))
        assertEquals("SS", autoTitleKey("ßtraße", 2))
        assertEquals("MO", autoTitleKey(" Mort", 2))
        assertEquals("TH", autoTitleKey("Þorn", 2))
    }
}
