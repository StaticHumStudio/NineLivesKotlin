package com.ninelivesaudio.app.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SerializedSettingsStateTest {

    @Test
    fun `newer encrypted settings block stale legacy import`() {
        assertFalse(shouldImportLegacySettings(encryptedSettingsJson = "newer encrypted"))
        assertTrue(shouldImportLegacySettings(encryptedSettingsJson = null))
    }

    @Test
    fun `a stubborn legacy file is logged, not fatal`() {
        // The encrypted store is authoritative by the time this runs in every
        // readSettingsFromDisk call site (already held the settings, just
        // committed a migration, or defaults are about to replace it anyway),
        // so a failed delete must never fail the whole load.
        assertEquals(null, legacyCleanupFailureMessage(deleted = true))
        assertTrue(legacyCleanupFailureMessage(deleted = false)!!.isNotBlank())
    }

    @Test
    fun `auth token state publishes only after durable commit`() {
        var published: Boolean? = null

        try {
            persistAuthTokenChange(
                token = "new-token",
                commit = { false },
                publish = { published = it },
            )
            fail("Expected token commit failure")
        } catch (_: IllegalStateException) {
            // A failed secure write must not advertise the attempted token.
        }

        assertEquals(null, published)
    }

    @Test
    fun `auth token state publishes committed storage value`() {
        var committedToken: String? = null
        var published: Boolean? = null

        persistAuthTokenChange(
            token = "  durable-token  ",
            commit = {
                committedToken = it
                true
            },
            publish = { published = it },
        )

        assertEquals("durable-token", committedToken)
        assertEquals(true, published)
    }

    @Test
    fun `failed disk read stays retryable and cannot persist defaults`() = runBlocking {
        val state = SerializedSettingsState(TestSettings())
        var persisted: TestSettings? = null

        try {
            state.update(
                read = {
                    requireSuccessfulSettingsCommit(committed = false)
                    TestSettings()
                },
                persist = { persisted = it },
            ) { it.copy(changed = true) }
            fail("Expected disk read failure")
        } catch (_: IllegalStateException) {
            // A fallback must not become loaded or reach persistence.
        }

        assertEquals(null, persisted)
        assertEquals(
            TestSettings(serverUrl = "https://retained.example"),
            state.load { TestSettings(serverUrl = "https://retained.example") },
        )
    }

    @Test
    fun `failed legacy cleanup cannot publish migrated settings`() = runBlocking {
        val state = SerializedSettingsState(TestSettings())

        try {
            state.load {
                requireSuccessfulSettingsCommit(committed = true)
                requireSuccessfulLegacySettingsDelete(deleted = false)
                TestSettings(serverUrl = "https://stale-legacy.example")
            }
            fail("Expected legacy cleanup failure")
        } catch (_: IllegalStateException) {
            // The migrated value must not close the load gate while plaintext remains.
        }

        assertEquals(
            TestSettings(serverUrl = "https://newer-encrypted.example"),
            state.load { TestSettings(serverUrl = "https://newer-encrypted.example") },
        )
    }

    @Test
    fun `failed update preserves last durable value and retries`() = runBlocking {
        val published = mutableListOf<TestSettings>()
        val state = SerializedSettingsState(TestSettings()) { published += it }
        val durable = TestSettings(serverUrl = "https://durable.example")
        state.load { durable }

        try {
            state.update(
                read = { error("already loaded") },
                persist = { error("encrypted write failed") },
            ) { it.copy(serverUrl = "https://uncommitted.example", changed = true) }
            fail("Expected persistence failure")
        } catch (_: IllegalStateException) {
            // The transformed value was never durable and must not replace memory.
        }

        assertEquals(durable, state.load { error("must reuse last durable value") })
        assertEquals(listOf(durable), published)

        var persisted: TestSettings? = null
        val retried = state.update(
            read = { error("already loaded") },
            persist = { persisted = it },
        ) { it.copy(changed = true) }
        assertEquals(durable.copy(changed = true), retried)
        assertEquals(retried, persisted)
    }

    private data class TestSettings(val serverUrl: String = "", val changed: Boolean = false)

    @Test
    fun `update waits for disk load and preserves loaded fields`() = runBlocking {
        val state = SerializedSettingsState(TestSettings())
        val readStarted = CompletableDeferred<Unit>()
        val allowRead = CompletableDeferred<Unit>()
        var persisted = TestSettings()

        val load = async {
            state.load {
                readStarted.complete(Unit)
                allowRead.await()
                TestSettings(serverUrl = "https://server.example")
            }
        }
        readStarted.await()

        val update = async {
            state.update(
                read = { error("update must reuse the in-flight load") },
                persist = { persisted = it },
            ) { it.copy(changed = true) }
        }

        allowRead.complete(Unit)
        load.await()
        update.await()

        assertEquals(TestSettings("https://server.example", changed = true), persisted)
    }
}
