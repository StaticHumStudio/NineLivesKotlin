package com.ninelivesaudio.app.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ninelivesaudio.app.NineLivesApp
import com.ninelivesaudio.app.data.local.LocalLibraryTestKit.Companion.LIB
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Issue #93: a rescan that reads new chapter names must not move the listener's place. */
@RunWith(AndroidJUnit4::class)
class LocalRescanInstrumentedTest {

    private val app = InstrumentationRegistry.getInstrumentation()
        .targetContext.applicationContext as NineLivesApp
    private lateinit var database: AppDatabase
    private lateinit var kit: LocalLibraryTestKit

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        kit = LocalLibraryTestKit(app, database)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun aRescanRenamesChaptersAndKeepsThePlaceAndBookmarks() = runBlocking {
        kit.seedLibrary()
        val files = listOf("a01.mp3", "a02.mp3")
        val untagged = kit.scanned("loc-1", "Carmilla", "Carmilla", files.map { Triple(it, 1_000L, null) })
        kit.repository.importLocalBooks(LIB, listOf(untagged))
        assertEquals(listOf("a01", "a02"), kit.repository.getById("loc-1")!!.chapters.map { it.title })

        database.audioBookDao().updateLocalPosition("loc-1", 1234.5, 0.4, 0)
        kit.progress("loc-1", 1234.5)
        kit.bookmark("loc-1", 60.0, "First")
        kit.bookmark("loc-1", 900.0, "Second")

        val tagged = kit.scanned(
            "loc-1", "Carmilla", "Carmilla",
            listOf(Triple("a01.mp3", 1_000L, "Chapter 01: A Guest"), Triple("a02.mp3", 1_000L, "Chapter 02: The Inn")),
        )
        kit.repository.importLocalBooksCarryingMoves(LIB, listOf(tagged), listOf("loc-1"), listOf("loc-1"))

        val row = database.audioBookDao().getById("loc-1")!!
        assertEquals(listOf("Chapter 01: A Guest", "Chapter 02: The Inn"), kit.repository.getById("loc-1")!!.chapters.map { it.title })
        assertEquals(1234.5, row.currentTimeSeconds, 0.0)
        assertEquals(0.4, row.progress, 0.0)
        assertEquals(1234.5, database.playbackProgressDao().getByAudioBookId("loc-1")!!.positionSeconds, 0.0)
        assertEquals(
            listOf(60.0 to "First", 900.0 to "Second"),
            database.localBookmarkDao().getByAudioBookId("loc-1").map { it.time to it.title },
        )
    }
}
