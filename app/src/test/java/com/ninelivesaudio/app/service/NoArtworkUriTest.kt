package com.ninelivesaudio.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android Auto cannot load an artworkUri: it rejects cleartext http, cannot
 * send the sign-in header a server needs, and cannot open this app's file://
 * or content:// covers. Art must stay embedded bytes (issue #89, #1). This
 * pins it: no main source may call setArtworkUri.
 */
class NoArtworkUriTest {

    private val call = Regex("""\bsetArtworkUri\s*\(""")

    private fun hits(text: String): List<Int> =
        text.lines().mapIndexedNotNull { index, line -> if (call.containsMatchIn(line)) index + 1 else null }

    @Test
    fun `the scanner finds a setArtworkUri call`() {
        assertEquals(listOf(1), hits(".setArtworkUri(uri)"))
        assertEquals(listOf(2), hits("val b = MediaMetadata.Builder()\n    .setArtworkUri (uri)"))
        assertEquals(emptyList<Int>(), hits("// never set artworkUri\n.setArtworkData(bytes, type)"))
    }

    @Test
    fun `no main source ever calls setArtworkUri`() {
        val root = File(System.getProperty("user.dir"), "src/main/java")
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("walked only ${files.size} files under $root", files.size > 100)
        val offenders = files.flatMap { file -> hits(file.readText()).map { "${file.relativeTo(root)}:$it" } }
        assertEquals(emptyList<String>(), offenders)
    }
}
