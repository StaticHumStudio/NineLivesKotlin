package com.ninelivesaudio.app.service.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A local folder import's tracks are SAF document URIs. Deleting a server
 * download has to know which files on disk they are, so it never takes audio
 * the import plays (#88).
 */
class DownloadPathsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val primary = File("/storage/emulated/0")
    private val sdCard = File("/storage/1234-5678")

    private fun volumes(id: String): File? = when (id) {
        "primary" -> primary
        "1234-5678" -> sdCard
        else -> null
    }

    @Test
    fun `a primary storage track uri names its file`() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3AAB" +
            "/document/primary%3AAB%2FAuthor%20-%20Title%20%5Babcd1234%5D%2F01.mp3"

        assertEquals(File(primary, "AB/Author - Title [abcd1234]/01.mp3"), safDocumentFile(uri, ::volumes))
    }

    @Test
    fun `a removable volume track uri names its file on that volume`() {
        val uri = "content://com.android.externalstorage.documents/tree/1234-5678%3AAudio" +
            "/document/1234-5678%3AAudio%2FBook%2F01.mp3"

        assertEquals(File(sdCard, "Audio/Book/01.mp3"), safDocumentFile(uri, ::volumes))
    }

    @Test
    fun `a home documents uri names a file under Documents`() {
        val uri = "content://com.android.externalstorage.documents/tree/home%3AAudio" +
            "/document/home%3AAudio%2F01.mp3"

        assertEquals(File(primary, "Documents/Audio/01.mp3"), safDocumentFile(uri, ::volumes))
    }

    @Test
    fun `a plus sign in a file name stays a plus sign`() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3AAB" +
            "/document/primary%3AAB%2FA%2BB%2F01.mp3"

        assertEquals(File(primary, "AB/A+B/01.mp3"), safDocumentFile(uri, ::volumes))
    }

    @Test
    fun `another provider's uri names no file`() {
        assertNull(
            safDocumentFile(
                "content://com.android.providers.media.documents/document/audio%3A1234",
                ::volumes,
            ),
        )
        assertNull(safDocumentFile("file:///storage/emulated/0/AB/01.mp3", ::volumes))
        assertNull(safDocumentFile("/storage/emulated/0/AB/01.mp3", ::volumes))
    }

    @Test
    fun `a volume this device does not have names no file`() {
        val uri = "content://com.android.externalstorage.documents/tree/9999-0000%3AAudio" +
            "/document/9999-0000%3AAudio%2F01.mp3"

        assertNull(safDocumentFile(uri, ::volumes))
    }

    @Test
    fun `a document id that climbs out with dot dot names no file`() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3AAB" +
            "/document/primary%3AAB%2F..%2F..%2Fsecret.mp3"

        assertNull(safDocumentFile(uri, ::volumes))
    }

    @Test
    fun `a tree uri with no document names no file`() {
        assertNull(safDocumentFile("content://com.android.externalstorage.documents/tree/primary%3AAB", ::volumes))
    }

    @Test
    fun `a downloads provider raw uri names its file`() {
        val uri = "content://com.android.providers.downloads.documents/tree/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2FAB" +
            "/document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2FAB%2FBook%2F01.mp3"

        assertEquals(File("/storage/emulated/0/Download/AB/Book/01.mp3"), safDocumentFile(uri, ::volumes))
    }

    @Test
    fun `a downloads provider id that is not a raw path names no file`() {
        assertNull(safDocumentFile("content://com.android.providers.downloads.documents/document/msf%3A1234", ::volumes))
        assertNull(safDocumentFile("content://com.android.providers.downloads.documents/document/1234", ::volumes))
        assertNull(
            safDocumentFile(
                "content://com.android.providers.downloads.documents/document/raw%3A%2Fstorage%2F..%2Fdata%2F01.mp3",
                ::volumes,
            ),
        )
    }

    @Test
    fun `a plain leaf name stays inside the folder`() {
        val dir = tempFolder.newFolder("book")

        assertEquals(File(dir, "01.mp3"), containedTrackFile(dir, "01.mp3"))
    }

    @Test
    fun `dot dot and dot names are refused`() {
        val dir = tempFolder.newFolder("book")

        assertNull(containedTrackFile(dir, ".."))
        assertNull(containedTrackFile(dir, "."))
        assertNull(containedTrackFile(dir, ""))
        assertNull(containedTrackFile(dir, "a/b.mp3"))
    }

    @Test
    fun `a stored track path outside the book folder is refused`() {
        val dir = tempFolder.newFolder("book")
        val other = tempFolder.newFolder("other")

        assertNull(trackFileInside(dir, File(other, "01.mp3").absolutePath))
        assertNull(trackFileInside(dir, File(dir, "sub/01.mp3").absolutePath))
        assertNull(trackFileInside(dir, File(dir, "../other/01.mp3").path))
        assertNull(trackFileInside(dir, "content://x/document/y"))
    }

    @Test
    fun `a file uri inside the folder is accepted`() {
        val dir = tempFolder.newFolder("book")
        val track = File(dir, "01.mp3")

        assertEquals(track.canonicalFile, trackFileInside(dir, track.toURI().toString())?.canonicalFile)
        assertEquals(track.canonicalFile, trackFileInside(dir, track.absolutePath)?.canonicalFile)
    }
}
