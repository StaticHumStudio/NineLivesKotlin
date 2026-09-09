package com.ninelivesaudio.app.data.local

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ninelivesaudio.app.data.local.entity.PendingProgressEntity
import com.ninelivesaudio.app.data.local.entity.PlaybackProgressEntity
import com.ninelivesaudio.app.data.remote.ActiveRemoteScope
import com.ninelivesaudio.app.data.remote.ApiService
import com.ninelivesaudio.app.data.remote.AudiobookshelfApi
import com.ninelivesaudio.app.data.remote.AuthInterceptor
import com.ninelivesaudio.app.data.remote.FrozenBearer
import com.ninelivesaudio.app.data.remote.FrozenRemoteRequest
import com.ninelivesaudio.app.data.remote.RemoteOwner
import com.ninelivesaudio.app.data.remote.RemoteTarget
import com.ninelivesaudio.app.data.remote.ServerRoute
import com.ninelivesaudio.app.data.repository.ProgressRepository
import com.ninelivesaudio.app.data.repository.ProgressScope
import com.ninelivesaudio.app.service.SettingsManager
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room proof for the queue boundary C2b will activate. A and B deliberately
 * use one raw ABS item, so owner-only or item-only deletes are both observable.
 */
@RunWith(AndroidJUnit4::class)
class PendingProgressOwnerDaoInstrumentedTest {

    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun BReplacementAndCapturedAcknowledgementPreserveAAndLegacyRows() = runBlocking {
        val pending = database.pendingProgressDao()
        val scopeA = scope("account-a")
        val scopeB = scope("account-b")
        val rawItemId = "same-raw-abs-item"
        val itemA = scopeA.encodeIncoming(rawItemId)
        val itemB = scopeB.encodeIncoming(rawItemId)

        val aId = pending.insert(row(ownerKey = scopeA.ownerKey, itemId = itemA, currentTime = 10.0))
        val bOldId = pending.insert(row(ownerKey = scopeB.ownerKey, itemId = itemB, currentTime = 20.0))
        val legacyId = pending.insert(row(ownerKey = null, itemId = rawItemId, currentTime = 30.0))

        assertEquals(listOf(bOldId), pending.getForOwnerAndItem(scopeB.ownerKey, itemB).map { it.id })
        assertEquals(1, pending.countDeliverableForOwner(scopeB.ownerKey))

        val bNewId = pending.saveProgressAndEnqueue(
            ownerKey = scopeB.ownerKey,
            progress = PlaybackProgressEntity(itemB, positionSeconds = 40.0, updatedAt = TIMESTAMP),
            pending = row(ownerKey = scopeB.ownerKey, itemId = itemB, currentTime = 40.0),
        )

        assertEquals(listOf(bNewId), pending.getForOwnerAndItem(scopeB.ownerKey, itemB).map { it.id })
        assertEquals(listOf(aId), pending.getForOwnerAndItem(scopeA.ownerKey, itemA).map { it.id })
        assertEquals(listOf(legacyId), pending.getLegacyUnowned().map { it.id })
        assertEquals(1, pending.countDeliverableForOwner(scopeB.ownerKey))

        pending.deleteIdsForOwnerAndItem(scopeB.ownerKey, itemB, listOf(bNewId))

        assertEquals(emptyList<Long>(), pending.getForOwnerAndItem(scopeB.ownerKey, itemB).map { it.id })
        assertEquals(listOf(aId), pending.getForOwnerAndItem(scopeA.ownerKey, itemA).map { it.id })
        assertEquals(listOf(legacyId), pending.getLegacyUnowned().map { it.id })
        assertNull(database.playbackProgressDao().getByAudioBookId(rawItemId))
        assertEquals(40.0, database.playbackProgressDao().getByAudioBookId(itemB)?.positionSeconds)
    }

    @Test
    fun staleRemoteScopeRollsBackTheRealRoomTransactionBeforeAnyProgressOrQueueWrite() = runBlocking {
        val scope = scope("account-a")
        val itemId = scope.encodeIncoming("same-raw-abs-item")
        val repository = ProgressRepository(
            database = database,
            playbackProgressDao = database.playbackProgressDao(),
            pendingProgressDao = database.pendingProgressDao(),
            apiService = ApiService(
                api = noNetworkApi(),
                authInterceptor = AuthInterceptor(),
                settingsManager = SettingsManager(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                ),
            ),
        )
        val transactionEntered = CompletableDeferred<Unit>()
        val releaseTransaction = CompletableDeferred<Unit>()
        val scopeChecks = AtomicInteger()
        val blocker = async(Dispatchers.Default) {
            database.withTransaction {
                transactionEntered.complete(Unit)
                releaseTransaction.await()
            }
        }
        transactionEntered.await()

        val staleWrite = async(Dispatchers.Default) {
            repository.saveScopedProgressAndEnqueue(
                progressScope = ProgressScope.Remote(scope),
                itemId = itemId,
                currentTime = 12.0,
                isFinished = false,
                duration = 600.0,
                isRemoteScopeCurrent = { scopeChecks.incrementAndGet() <= 2 },
            )
        }
        withTimeout(1_000) {
            while (scopeChecks.get() < 2) yield()
        }
        releaseTransaction.complete(Unit)
        val saved = staleWrite.await()
        blocker.await()

        assertFalse("A third check at the transaction fence must reject the stale scope", saved)
        assertNull(database.playbackProgressDao().getByAudioBookId(itemId))
        assertEquals(emptyList<PendingProgressEntity>(), database.pendingProgressDao().getAll())
    }

    private fun row(ownerKey: String?, itemId: String, currentTime: Double) = PendingProgressEntity(
        itemId = itemId,
        ownerKey = ownerKey,
        currentTime = currentTime,
        duration = 600.0,
        isAtomic = 1,
        timestamp = TIMESTAMP,
    )

    private fun scope(accountId: String): ActiveRemoteScope {
        val route = requireNotNull(ServerRoute.parse("https://abs.example.test"))
        val owner = RemoteTarget(RemoteOwner(route, accountId), authGeneration = 1)
        return ActiveRemoteScope(
            FrozenRemoteRequest(
                route = route,
                owner = owner,
                bearer = FrozenBearer("fixture-$accountId", route, authGeneration = 1),
                routeRevision = 1,
            ),
        )
    }

    private fun noNetworkApi(): AudiobookshelfApi = Proxy.newProxyInstance(
        AudiobookshelfApi::class.java.classLoader,
        arrayOf(AudiobookshelfApi::class.java),
        InvocationHandler { _, method, _ -> error("Unexpected API call in Room transaction test: ${method.name}") },
    ) as AudiobookshelfApi

    private companion object {
        const val TIMESTAMP = "2026-09-08T00:00:00Z"
    }
}
