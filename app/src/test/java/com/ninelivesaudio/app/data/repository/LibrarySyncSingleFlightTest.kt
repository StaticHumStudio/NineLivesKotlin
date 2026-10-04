package com.ninelivesaudio.app.data.repository

import com.ninelivesaudio.app.data.remote.RemoteResult
import com.ninelivesaudio.app.domain.model.AudioBook
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sign-in's sync and the Library's first load each downloaded the whole
 * library, one after the other (214 s instead of 107 s on a 51-page shelf).
 * A request that arrives while a sync of the same library runs now shares it.
 */
class LibrarySyncSingleFlightTest {

    private fun flightScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test
    fun `a second request while a sync runs shares its result and fetches nothing`() = runBlocking {
        val scope = flightScope()
        val flights = LibrarySyncSingleFlight<Int>(scope)
        val fetches = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fetcher: suspend () -> Int = {
            fetches.incrementAndGet()
            started.complete(Unit)
            release.await()
            51
        }

        val first = async { flights.run("lib", LibrarySyncKind.FULL, fetcher) }
        started.await()
        val second = async { flights.run("lib", LibrarySyncKind.FULL, fetcher) }
        val check = async { flights.run("lib", LibrarySyncKind.CHECK, fetcher) }
        yield()
        release.complete(Unit)

        assertEquals(51, first.await())
        assertEquals(51, second.await())
        assertEquals(51, check.await())
        assertEquals(1, fetches.get())
        scope.cancel()
    }

    @Test
    fun `a full refresh asked for during a check waits and then runs its own`() = runBlocking {
        val scope = flightScope()
        val flights = LibrarySyncSingleFlight<String>(scope)
        val order = mutableListOf<String>()
        val checkStarted = CompletableDeferred<Unit>()
        val releaseCheck = CompletableDeferred<Unit>()

        val check = async {
            flights.run("lib", LibrarySyncKind.CHECK) {
                checkStarted.complete(Unit)
                releaseCheck.await()
                synchronized(order) { order += "check" }
                "unchanged"
            }
        }
        checkStarted.await()
        val full = async {
            flights.run("lib", LibrarySyncKind.FULL) {
                synchronized(order) { order += "full" }
                "downloaded"
            }
        }
        yield()
        assertFalse(full.isCompleted)
        releaseCheck.complete(Unit)

        assertEquals("unchanged", check.await())
        assertEquals("downloaded", full.await())
        assertEquals(listOf("check", "full"), order)
        scope.cancel()
    }

    @Test
    fun `a check that turns into a full download is shared with a full refresh already waiting`() = runBlocking {
        val scope = flightScope()
        val flights = LibrarySyncSingleFlight<String>(scope)
        val fetches = AtomicInteger()
        val checkStarted = CompletableDeferred<Unit>()
        val upgraded = CompletableDeferred<Unit>()
        val releaseDownload = CompletableDeferred<Unit>()

        val check = async {
            flights.run("lib", LibrarySyncKind.CHECK) {
                checkStarted.complete(Unit)
                upgraded.await()
                // No watermark: the check decides on a full download.
                flights.upgradeToFull("lib")
                fetches.incrementAndGet()
                releaseDownload.await()
                "51 pages"
            }
        }
        checkStarted.await()
        val signIn = async { flights.run("lib", LibrarySyncKind.FULL) { fetches.incrementAndGet(); "second download" } }
        yield()
        upgraded.complete(Unit)
        releaseDownload.complete(Unit)

        assertEquals("51 pages", check.await())
        assertEquals("51 pages", signIn.await())
        assertEquals(1, fetches.get())
        scope.cancel()
    }

    @Test
    fun `different libraries do not share`() = runBlocking {
        val scope = flightScope()
        val flights = LibrarySyncSingleFlight<String>(scope)
        val a = async { flights.run("a", LibrarySyncKind.FULL) { "a" } }
        val b = async { flights.run("b", LibrarySyncKind.FULL) { "b" } }
        assertEquals("a", a.await())
        assertEquals("b", b.await())
        scope.cancel()
    }

    @Test
    fun `a new account never joins the previous account's sync of the same library`() = runBlocking {
        val scope = flightScope()
        val flights = LibrarySyncSingleFlight<String>(scope)
        val server = "https://abs.example.net"
        val accountA = LibrarySyncIdentity(SyncAccountKey(server, "alice"), sessionHash = 11)
        val accountB = LibrarySyncIdentity(SyncAccountKey(server, "bob"), sessionHash = 22)
        // Two token sign-ins keep an empty username and differ only by session.
        val tokenA = LibrarySyncIdentity(SyncAccountKey(server, ""), sessionHash = 33)
        val tokenB = LibrarySyncIdentity(SyncAccountKey(server, ""), sessionHash = 44)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val slowA = async {
            flights.run(accountA.flightKey("lib"), LibrarySyncKind.FULL) {
                started.complete(Unit)
                release.await()
                "alice's catalog"
            }
        }
        started.await()
        try {
            // Joining alice's flight would wait on her download, so time out.
            val bob = withTimeout(5_000) {
                flights.run(accountB.flightKey("lib"), LibrarySyncKind.FULL) { "bob's catalog" }
            }
            assertEquals("bob's catalog", bob)
            assertFalse(slowA.isCompleted)
        } finally {
            release.complete(Unit)
        }
        assertEquals("alice's catalog", slowA.await())
        assertTrue(tokenA.flightKey("lib") != tokenB.flightKey("lib"))
        scope.cancel()
    }

    @Test
    fun `a download that outlives its account stops writing and prunes nothing`() = runBlocking {
        val cache = mutableListOf("bob-book")
        var signedIn = "alice"
        val result = runLibraryItemSyncPass(
            libraryId = "lib",
            fetchPages = { onPage ->
                onPage(listOf(AudioBook(id = "alice-1")))
                signedIn = "bob"
                onPage(listOf(AudioBook(id = "alice-2")))
                RemoteResult.Ok(2)
            },
            mergeItems = { it },
            upsertAll = { books -> books.forEach { cache += it.id } },
            cachedNonDownloadedIds = { cache.toList() },
            deleteByIds = { _, ids -> cache.removeAll(ids) },
            deleteAllServerBooks = { cache.clear() },
            writeIfCurrent = { write -> if (signedIn == "alice") { write(); true } else false },
        )
        assertEquals(RemoteResult.Failed(SYNC_ACCOUNT_CHANGED), result)
        // Page one landed before the switch. Nothing after it, and bob's
        // book was not pruned by alice's catalog.
        assertEquals(listOf("bob-book", "alice-1"), cache)
    }

    @Test
    fun `a sign-in waits for a prune already past its account check`() = runBlocking {
        // Alice's prune passes the account check, then suspends reading the
        // cached ids. Bob signs in and his sync saves a book. When alice
        // resumes, her prune must not delete it: the sign-in has to wait
        // for the whole prune, not just for the check in front of it.
        val authLock = Mutex()
        var signedIn = "alice"
        val cache = mutableListOf("shared", "alice-gone")
        val pruneReading = CompletableDeferred<Unit>()
        val releasePrune = CompletableDeferred<Unit>()
        val gate: suspend (suspend () -> Unit) -> Boolean = { write ->
            authLock.withLock { if (signedIn == "alice") { write(); true } else false }
        }
        val alice = async {
            runLibraryItemSyncPass(
                libraryId = "lib",
                fetchPages = { onPage ->
                    onPage(listOf(AudioBook(id = "shared")))
                    RemoteResult.Ok(1)
                },
                mergeItems = { it },
                upsertAll = { books -> books.forEach { if (it.id !in cache) cache += it.id } },
                cachedNonDownloadedIds = {
                    pruneReading.complete(Unit)
                    releasePrune.await()
                    cache.toList()
                },
                deleteByIds = { _, ids -> cache.removeAll(ids) },
                deleteAllServerBooks = { cache.clear() },
                writeIfCurrent = gate,
            )
        }
        pruneReading.await()
        val bob = launch {
            authLock.withLock { signedIn = "bob" }
            cache += "bob-book"
        }
        // Give bob every chance to get in while alice is suspended.
        withTimeoutOrNull(300) { bob.join() }
        releasePrune.complete(Unit)
        assertEquals(RemoteResult.Ok(1), alice.await())
        bob.join()
        assertEquals(listOf("shared", "bob-book"), cache)
    }

    @Test
    fun `a caller that leaves does not cancel the sync others are waiting on`() = runBlocking {
        val scope = flightScope()
        val flights = LibrarySyncSingleFlight<Int>(scope)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val saved = AtomicInteger()

        val leaving = launch {
            flights.run("lib", LibrarySyncKind.FULL) {
                started.complete(Unit)
                release.await()
                saved.incrementAndGet()
                7
            }
        }
        started.await()
        val staying = async { flights.run("lib", LibrarySyncKind.CHECK) { error("must join, not fetch") } }
        yield()
        leaving.cancelAndJoin()
        release.complete(Unit)

        assertEquals(7, staying.await())
        assertEquals(1, saved.get())
        scope.cancel()
    }

    @Test
    fun `a failed sync fails every waiter and the next request starts fresh`() = runBlocking {
        val scope = flightScope()
        val flights = LibrarySyncSingleFlight<Int>(scope)
        val failure = runCatching { flights.run("lib", LibrarySyncKind.FULL) { throw IllegalStateException("boom") } }
        assertTrue(failure.exceptionOrNull() is IllegalStateException)
        assertEquals(3, flights.run("lib", LibrarySyncKind.FULL) { 3 })
        scope.cancel()
    }

    @Test
    fun `joining rules`() {
        assertTrue(canJoinInFlight(LibrarySyncKind.FULL, LibrarySyncKind.FULL))
        assertTrue(canJoinInFlight(LibrarySyncKind.FULL, LibrarySyncKind.CHECK))
        assertTrue(canJoinInFlight(LibrarySyncKind.CHECK, LibrarySyncKind.CHECK))
        assertFalse(canJoinInFlight(LibrarySyncKind.CHECK, LibrarySyncKind.FULL))
    }

    @Test
    fun `pages are saved as they arrive and only a complete fetch prunes`() = runBlocking {
        val cache = mutableListOf("stale", "kept")
        val savedPages = mutableListOf<List<String>>()
        val complete = runLibraryItemSyncPass(
            libraryId = "lib",
            fetchPages = { onPage ->
                onPage(listOf(AudioBook(id = "kept"), AudioBook(id = "new1")))
                onPage(listOf(AudioBook(id = "new2")))
                RemoteResult.Ok(3)
            },
            mergeItems = { it },
            upsertAll = { books -> books.forEach { if (it.id !in cache) cache += it.id } },
            cachedNonDownloadedIds = { cache.toList() },
            deleteByIds = { _, ids -> cache.removeAll(ids) },
            deleteAllServerBooks = { cache.clear() },
            onPageSaved = { page -> savedPages += page.map { it.id } },
        )
        assertEquals(RemoteResult.Ok(3), complete)
        assertEquals(listOf(listOf("kept", "new1"), listOf("new2")), savedPages)
        assertEquals(setOf("kept", "new1", "new2"), cache.toSet())

        val partialCache = mutableListOf("stale")
        runLibraryItemSyncPass(
            libraryId = "lib",
            fetchPages = { onPage ->
                onPage(listOf(AudioBook(id = "new1")))
                RemoteResult.Partial(1, "page 1: HTTP 500")
            },
            mergeItems = { it },
            upsertAll = { books -> books.forEach { partialCache += it.id } },
            cachedNonDownloadedIds = { partialCache.toList() },
            deleteByIds = { _, ids -> partialCache.removeAll(ids) },
            deleteAllServerBooks = { partialCache.clear() },
        )
        assertEquals(listOf("stale", "new1"), partialCache)
    }

    @Test
    fun `a stale response still cannot resurrect a pruned book once flights serialize behind the lock`() = runBlocking {
        // A full refresh that could not join a running check queues behind it
        // on the library lock, so its newer prune lands after the older save.
        val scope = flightScope()
        val flights = LibrarySyncSingleFlight<RemoteResult<Int>>(scope)
        val lock = Mutex()
        val cache = mutableListOf("book-a", "book-b")
        val oldStarted = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        fun pass(books: List<String>, gate: suspend () -> Unit): suspend () -> RemoteResult<Int> = {
            runSerializedLibraryItemSync(
                mutex = lock,
                libraryId = "lib",
                fetchPages = { onPage ->
                    gate()
                    onPage(books.map { AudioBook(id = it) })
                    RemoteResult.Ok(books.size)
                },
                mergeItems = { it },
                upsertAll = { list -> list.forEach { if (it.id !in cache) cache += it.id } },
                cachedNonDownloadedIds = { cache.toList() },
                deleteByIds = { _, ids -> cache.removeAll(ids) },
                deleteAllServerBooks = { cache.clear() },
            )
        }
        val old = async {
            flights.run("lib", LibrarySyncKind.CHECK, pass(listOf("book-a", "book-b")) {
                oldStarted.complete(Unit)
                releaseOld.await()
            })
        }
        oldStarted.await()
        val newer = async { flights.run("lib", LibrarySyncKind.FULL, pass(listOf("book-a")) {}) }
        yield()
        releaseOld.complete(Unit)
        old.await()
        newer.await()
        assertEquals(listOf("book-a"), cache)
        scope.cancel()
    }
}
