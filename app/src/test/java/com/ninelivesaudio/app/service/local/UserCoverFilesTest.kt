package com.ninelivesaudio.app.service.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A cover the user picks lives in files/user_covers and only those files count as picks. */
class UserCoverFilesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var scanDir: File

    @Before
    fun setUp() {
        dir = tempFolder.newFolder("user_covers")
        scanDir = tempFolder.newFolder("local_covers")
    }

    @Test fun `a file in user covers is a user pick`() =
        assertTrue(isUserPickedCover(fileUriString(File(dir, "a-1.jpg")), dir))

    @Test fun `the scan's own cover is not a user pick`() =
        assertFalse(isUserPickedCover(fileUriString(File(scanDir, "a.jpg")), dir))

    @Test fun `content uris, server urls, null and junk are not user picks`() {
        listOf(null, "", "content://x/y.jpg", "https://abs.example/api/items/a/cover", "not a uri %%")
            .forEach { assertFalse(it.toString(), isUserPickedCover(it, dir)) }
    }

    @Test fun `a path that climbs out of user covers is not a user pick`() =
        assertFalse(isUserPickedCover("file://${dir.absolutePath}/../local_covers/a.jpg", dir))

    @Test fun `pick file names are unique per pick and safe for set aside ids`() {
        assertEquals("local_book_ab~1793480808266-5.jpg", userCoverFileName("local_book_ab~1793480808266", 5))
        assertEquals("a_b_c-1.jpg", userCoverFileName("a/b c", 1))
    }

    @Test fun `writing a pick leaves only the jpg behind`() {
        val f = writeUserCoverFile(byteArrayOf(1, 2, 3), dir, "a", 7)!!
        assertEquals("a-7.jpg", f.name)
        assertEquals(listOf("a-7.jpg"), dir.list()!!.toList())
        assertArrayEquals(byteArrayOf(1, 2, 3), f.readBytes())
    }

    @Test fun `an empty image is not written`() {
        assertNull(writeUserCoverFile(ByteArray(0), dir, "a", 7))
        assertEquals(0, dir.list()!!.size)
    }

    @Test fun `a pick in a folder that does not exist yet creates it`() {
        val fresh = File(tempFolder.root, "files/user_covers")
        val f = writeUserCoverFile(byteArrayOf(4), fresh, "a", 1)!!
        assertTrue(f.exists())
        assertTrue(isUserPickedCover(fileUriString(f), fresh))
    }

    @Test fun `a move copies the pick under the new book's name`() {
        val pick = writeUserCoverFile(byteArrayOf(9), dir, "a", 1)!!
        val copy = copyUserCoverFile(fileUriString(pick), dir, "b", 2)!!
        assertEquals("b-2.jpg", copy.name)
        assertTrue(pick.exists())
        assertArrayEquals(byteArrayOf(9), copy.readBytes())
    }

    @Test fun `a move with no pick copies nothing`() {
        assertNull(copyUserCoverFile(fileUriString(File(scanDir, "a.jpg").apply { writeBytes(byteArrayOf(1)) }), dir, "b", 2))
        assertNull(copyUserCoverFile(null, dir, "b", 2))
    }

    @Test fun `a move whose pick file is gone copies nothing`() =
        assertNull(copyUserCoverFile(fileUriString(File(dir, "a-1.jpg")), dir, "b", 2))

    @Test fun `delete removes a pick and refuses anything outside user covers`() {
        val pick = writeUserCoverFile(byteArrayOf(1), dir, "a", 1)!!
        val scan = File(scanDir, "a.jpg").apply { writeBytes(byteArrayOf(1)) }
        assertFalse(deleteUserCoverFile(fileUriString(scan), dir))
        assertTrue(scan.exists())
        assertTrue(deleteUserCoverFile(fileUriString(pick), dir))
        assertFalse(pick.exists())
    }

    @Test fun `a set aside book keeps its pick, otherwise the kept scan cover`() {
        val pick = fileUriString(File(dir, "a-1.jpg"))
        val kept = fileUriString(File(scanDir, "a~9.jpg"))
        assertEquals(pick, setAsideLocalCover(pick, kept, dir))
        assertEquals(kept, setAsideLocalCover(null, kept, dir))
        assertNull(setAsideLocalCover(null, null, dir))
    }

    @Test fun `a move carries the old pick only when the new row has none`() {
        val old = fileUriString(File(dir, "a-1.jpg"))
        val own = fileUriString(File(dir, "b-2.jpg"))
        assertEquals(old, coverToCarryOnMove(old, null, dir))
        assertNull(coverToCarryOnMove(old, own, dir))
        assertNull(coverToCarryOnMove(fileUriString(File(scanDir, "a.jpg")), null, dir))
    }

    // A pick whose file is gone (a phone transfer that brought the row but not the file)

    @Test fun `a pick whose file is missing counts as no pick, so the scan's art shows`() {
        assertNull(keptUserCover(fileUriString(File(dir, "gone-1.jpg")), dir))
    }

    @Test fun `a pick whose file is there is kept`() {
        val f = File(dir, "here-1.jpg").apply { writeBytes(byteArrayOf(1)) }
        assertEquals(fileUriString(f), keptUserCover(fileUriString(f), dir))
    }

    @Test fun `anything that is not a pick is kept as it is`() {
        assertNull(keptUserCover(null, dir))
        val scanArt = fileUriString(File(scanDir, "x.jpg"))
        assertEquals(scanArt, keptUserCover(scanArt, dir))
    }

    // The sweep after each scan

    private val hour = 60 * 60 * 1000L
    private val now = 10 * hour

    private fun file(name: String, ageMs: Long) = File(dir, name).apply {
        writeBytes(byteArrayOf(1))
        setLastModified(now - ageMs)
    }

    @Test fun `files no row points at and older than an hour are swept, temp files included`() {
        val kept = file("kept-1.jpg", 5 * hour)
        val orphan = file("orphan-1.jpg", 2 * hour)
        val oldTemp = file("pick-123.tmp", 2 * hour)
        val sweep = userCoverFilesToSweep(dir, listOf(fileUriString(kept), null, "content://x"), now)
        assertEquals(setOf(orphan.name, oldTemp.name), sweep.map { it.name }.toSet())
    }

    @Test fun `a fresh file is never swept, it may be a save in progress`() {
        file("new-1.jpg", 10 * 60 * 1000L)
        file("pick-9.tmp", 59 * 60 * 1000L)
        assertTrue(userCoverFilesToSweep(dir, emptyList(), now).isEmpty())
    }

    @Test fun `no folder yet is nothing to sweep`() =
        assertTrue(userCoverFilesToSweep(File(tempFolder.root, "missing"), emptyList(), now).isEmpty())
}
