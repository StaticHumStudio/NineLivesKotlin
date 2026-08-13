package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.AudioFile
import com.ninelivesaudio.app.service.ConnectivityMonitor.ConnectionStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemoteMediaAccessPolicyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val cachedServerBook = AudioBook(
        id = "server-book",
        isLocal = false,
        isDownloaded = false,
    )

    @Test
    fun `empty server configuration is blocked`() {
        val decision = remoteMediaAccessDecision(
            book = cachedServerBook,
            serverUrl = "",
            connectionStatus = ConnectionStatus.SERVER_UNREACHABLE,
        )

        assertTrue(decision is RemoteMediaAccessDecision.Blocked)
        assertFalse(decision.mayReplaceCurrentPlayerItem)
    }

    @Test
    fun `invalid server configuration is blocked`() {
        val decision = remoteMediaAccessDecision(
            book = cachedServerBook,
            serverUrl = "not a url",
            connectionStatus = ConnectionStatus.CONNECTED,
        )

        assertTrue(decision is RemoteMediaAccessDecision.Blocked)
        assertFalse(decision.mayReplaceCurrentPlayerItem)
    }

    @Test
    fun `valid but unreachable server is blocked`() {
        val decision = remoteMediaAccessDecision(
            book = cachedServerBook,
            serverUrl = "https://books.example.com",
            connectionStatus = ConnectionStatus.SERVER_UNREACHABLE,
        )

        assertTrue(decision is RemoteMediaAccessDecision.Blocked)
        assertFalse(decision.mayReplaceCurrentPlayerItem)
    }

    @Test
    fun `valid reachable server allows remote media`() {
        val decision = remoteMediaAccessDecision(
            book = cachedServerBook,
            serverUrl = "https://books.example.com/base",
            connectionStatus = ConnectionStatus.CONNECTED,
        )

        assertTrue(decision is RemoteMediaAccessDecision.Remote)
        assertTrue(decision.mayReplaceCurrentPlayerItem)
    }

    @Test
    fun `reachable server streams when downloaded marker points to a missing file`() {
        val decision = remoteMediaAccessDecision(
            book = cachedServerBook.copy(
                isDownloaded = true,
                localPath = "/missing/book.m4b",
            ),
            serverUrl = "https://books.example.com/base",
            connectionStatus = ConnectionStatus.CONNECTED,
            localDownloadAvailable = false,
        )

        assertTrue(decision is RemoteMediaAccessDecision.Remote)
        assertFalse(decision.usesLocalTracks())
    }

    @Test
    fun `valid local download works without server configuration`() {
        val decision = remoteMediaAccessDecision(
            book = cachedServerBook.copy(
                isDownloaded = true,
                localPath = "/data/user/0/com.ninelivesaudio.app.debug/files/book.m4b",
            ),
            serverUrl = "",
            connectionStatus = ConnectionStatus.SERVER_UNREACHABLE,
            localDownloadAvailable = true,
        )

        assertTrue(decision is RemoteMediaAccessDecision.LocalFile)
        assertTrue(decision.mayReplaceCurrentPlayerItem)
    }

    @Test
    fun `stale downloaded marker does not bypass missing server configuration`() {
        val decision = remoteMediaAccessDecision(
            book = cachedServerBook.copy(
                isDownloaded = true,
                localPath = "/missing/book.m4b",
            ),
            serverUrl = "",
            connectionStatus = ConnectionStatus.SERVER_UNREACHABLE,
            localDownloadAvailable = false,
        )

        assertTrue(decision is RemoteMediaAccessDecision.Blocked)
        assertFalse(decision.mayReplaceCurrentPlayerItem)
    }

    @Test
    fun `download directory requires playable audio`() {
        val downloadDirectory = tempFolder.newFolder("download")
        downloadDirectory.resolve("cover.jpg").createNewFile()
        val book = cachedServerBook.copy(
            isDownloaded = true,
            localPath = downloadDirectory.absolutePath,
            audioFiles = listOf(
                AudioFile(filename = "one.m4b"),
                AudioFile(filename = "two.m4b"),
            ),
        )

        assertFalse(hasUsableLocalDownload(book))

        downloadDirectory.resolve("one.m4b").writeBytes(byteArrayOf(1))

        assertFalse(hasUsableLocalDownload(book))

        downloadDirectory.resolve("two.m4b").writeBytes(byteArrayOf(1))

        assertTrue(hasUsableLocalDownload(book))
    }
}
