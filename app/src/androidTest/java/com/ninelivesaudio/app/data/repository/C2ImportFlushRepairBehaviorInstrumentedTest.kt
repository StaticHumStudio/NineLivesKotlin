package com.ninelivesaudio.app.data.repository

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import dagger.Lazy
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ninelivesaudio.app.data.local.AppDatabase
import com.ninelivesaudio.app.data.local.dao.AudioBookDao
import com.ninelivesaudio.app.data.local.entity.AudioBookEntity
import com.ninelivesaudio.app.data.local.entity.PendingProgressEntity
import com.ninelivesaudio.app.data.local.entity.PlaybackProgressEntity
import com.ninelivesaudio.app.data.remote.ActiveRemoteScope
import com.ninelivesaudio.app.data.remote.ApiService
import com.ninelivesaudio.app.data.remote.AudiobookshelfApi
import com.ninelivesaudio.app.data.remote.AuthInterceptor
import com.ninelivesaudio.app.data.remote.dto.ApiMeResponse
import com.ninelivesaudio.app.data.remote.dto.ApiPlaybackSession
import com.ninelivesaudio.app.data.remote.dto.UpdateProgressRequest
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.PlaybackSessionInfo
import com.ninelivesaudio.app.entitlement.EntitlementCachePrefs
import com.ninelivesaudio.app.entitlement.EntitlementPrefs
import com.ninelivesaudio.app.entitlement.EntitlementRepository
import com.ninelivesaudio.app.entitlement.EffectiveSettingsRepository
import com.ninelivesaudio.app.entitlement.WorkManagerTrialReminderScheduler
import com.ninelivesaudio.app.service.ConnectivityMonitor
import com.ninelivesaudio.app.service.ApiServiceRemoteScopePublicationFence
import com.ninelivesaudio.app.service.PlaybackManager
import com.ninelivesaudio.app.service.RemotePlaybackSessionCoordinatorFactory
import com.ninelivesaudio.app.service.PlaybackState
import com.ninelivesaudio.app.service.SettingsManager
import com.ninelivesaudio.app.service.StaleSessionProbe
import com.ninelivesaudio.app.service.SyncManager
import com.ninelivesaudio.app.service.playbackProgressSource
import com.ninelivesaudio.app.service.staleSessionProbe
import com.ninelivesaudio.app.service.local.LocalFolderAccess
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Response
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Production-path RED coverage for repair 1b. Room and ProgressRepository are
 * real. Only the HTTP edge is a controlled proxy so an authenticated scope and
 * dispatched PATCH payload remain observable.
 */
@RunWith(AndroidJUnit4::class)
class C2ImportFlushRepairBehaviorInstrumentedTest {

    private lateinit var database: AppDatabase
    private lateinit var fixtureContext: C2RepairFixtureContext
    private lateinit var transport: C2RepairTransport
    private lateinit var apiService: ApiService
    private lateinit var repository: ProgressRepository
    private lateinit var settingsManager: SettingsManager

    @Before
    fun setUp() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        fixtureContext = C2RepairFixtureContext(targetContext)
        database = Room.inMemoryDatabaseBuilder(targetContext, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        transport = C2RepairTransport()
        settingsManager = SettingsManager(fixtureContext)
        apiService = ApiService(
            api = transport.api(),
            authInterceptor = AuthInterceptor(),
            settingsManager = settingsManager,
        )
        repository = ProgressRepository(
            database = database,
            playbackProgressDao = database.playbackProgressDao(),
            pendingProgressDao = database.pendingProgressDao(),
            apiService = apiService,
        )
    }

    @After
    fun tearDown() {
        database.close()
        fixtureContext.clearFixtureStorage()
    }

    @Test
    fun importRollsBackDurableAndShelfWritesWhenScopeExpiresDuringHeldShelfCallback() = runBlocking {
        val scopeA = loginAs(OWNER_A, TOKEN_A)
        val itemId = scopeA.encodeIncoming(RAW_IMPORT_ITEM)
        val priorProgress = PlaybackProgressEntity(itemId, positionSeconds = 10.0, updatedAt = "before")
        database.playbackProgressDao().upsert(priorProgress)
        database.audioBookDao().upsert(book(itemId, currentTime = 10.0, progress = 0.1))

        val shelfMutationCommitted = CompletableDeferred<Unit>()
        val releaseShelfCallback = CompletableDeferred<Unit>()
        val result = async(Dispatchers.Default) {
            repository.importServerProgressIfNoPending(
                scope = scopeA,
                progress = PlaybackProgressEntity(itemId, positionSeconds = 20.0, updatedAt = "imported"),
                importToken = repository.progressImportToken(scopeA),
            ) {
                database.audioBookDao().updateProgress(itemId, 20.0, 0.2, 0)
                shelfMutationCommitted.complete(Unit)
                releaseShelfCallback.await()
            }
        }

        shelfMutationCommitted.await()
        loginAs(OWNER_B, TOKEN_B)
        releaseShelfCallback.complete(Unit)

        assertFalse(result.await())
        assertEquals(priorProgress, database.playbackProgressDao().getByAudioBookId(itemId))
        val shelf = requireNotNull(database.audioBookDao().getById(itemId))
        assertEquals(10.0, shelf.currentTimeSeconds, 0.0)
        assertEquals(0.1, shelf.progress, 0.0)
    }

    @Test
    fun flushContinuesToLaterOwnerItemWhenFirstScannedItemVanishesBeforeItsLock() = runBlocking {
        val scope = loginAs(OWNER_A, TOKEN_A)
        val vanishedItem = scope.encodeIncoming(RAW_VANISHED_ITEM)
        val laterItem = scope.encodeIncoming(RAW_LATER_ITEM)
        seedPending(scope, vanishedItem, currentTime = 10.0, duration = 100.0)
        seedPending(scope, laterItem, currentTime = 30.0, duration = 100.0)

        val flushed = repository.flushPendingProgress(
            scope = scope,
            beforeItemLock = { scannedItem ->
                if (scannedItem == vanishedItem) {
                    database.pendingProgressDao().deleteForOwnerAndItem(scope.ownerKey, vanishedItem)
                }
            },
        )

        assertTrue(flushed)
        assertEquals(listOf(RAW_LATER_ITEM), transport.deliveredItemIds)
        assertEquals(emptyList<PendingProgressEntity>(), database.pendingProgressDao().getAll())
    }

    @Test
    fun flushLeavesZeroDurationRowButStillDeliversLaterValidOwnerItem() = runBlocking {
        val scope = loginAs(OWNER_A, TOKEN_A)
        val zeroDurationItem = scope.encodeIncoming(RAW_ZERO_DURATION_ITEM)
        val laterItem = scope.encodeIncoming(RAW_LATER_ITEM)
        seedPending(scope, zeroDurationItem, currentTime = 10.0, duration = 0.0)
        seedPending(scope, laterItem, currentTime = 30.0, duration = 100.0)

        assertTrue(repository.flushPendingProgress(scope))

        assertEquals(listOf(RAW_LATER_ITEM), transport.deliveredItemIds)
        assertNotNull(database.pendingProgressDao().getForOwnerAndItem(scope.ownerKey, zeroDurationItem).singleOrNull())
        assertEquals(emptyList<PendingProgressEntity>(), database.pendingProgressDao().getForOwnerAndItem(scope.ownerKey, laterItem))
    }

    @Test
    fun pendingProgressCountForCurrentScopeExcludesForeignAndLegacyRows() = runBlocking {
        val scopeB = loginAs(OWNER_B, TOKEN_B)
        val scopeA = loginAs(OWNER_A, TOKEN_A)
        seedPending(scopeA, scopeA.encodeIncoming("a-item"), currentTime = 10.0, duration = 100.0)
        seedPending(scopeB, scopeB.encodeIncoming("b-item"), currentTime = 20.0, duration = 100.0)
        database.pendingProgressDao().insert(
            PendingProgressEntity(
                ownerKey = null,
                itemId = "legacy-item",
                currentTime = 30.0,
                duration = 100.0,
                isAtomic = 1,
                timestamp = "2026-09-08T00:00:00Z",
            ),
        )

        assertEquals(1, repository.pendingProgressCount(scopeA))
    }

    @Test
    fun queuedOnlySavePersistsButDoesNotDispatchRemotePatch() = runBlocking {
        val scope = loginAs(OWNER_A, TOKEN_A)
        val itemId = scope.encodeIncoming(RAW_QUEUED_ONLY_ITEM)

        val outcome = repository.savePushOrEnqueueProgress(
            scope = scope,
            itemId = itemId,
            currentTime = 25.0,
            isFinished = false,
            duration = 100.0,
            pushToServer = false,
        )

        assertTrue(outcome.persisted)
        assertFalse(outcome.remoteDelivered)
        assertEquals(emptyList<String>(), transport.deliveredItemIds)
        assertNotNull(database.pendingProgressDao().getForOwnerAndItem(scope.ownerKey, itemId).singleOrNull())
    }

    @Test
    fun sameIdentityDirectAndFlushDoNotOverlapWhileDifferentItemDispatches() = runBlocking {
        val scope = loginAs(OWNER_A, TOKEN_A)
        val sameItem = scope.encodeIncoming(RAW_SAME_IDENTITY_ITEM)
        val otherItem = scope.encodeIncoming(RAW_OTHER_IDENTITY_ITEM)
        val heldPatch = transport.holdPatch(RAW_SAME_IDENTITY_ITEM)

        val direct = async(Dispatchers.Default) {
            repository.savePushOrEnqueueProgress(
                scope = scope,
                itemId = sameItem,
                currentTime = 10.0,
                isFinished = false,
                duration = 100.0,
                pushToServer = true,
            )
        }
        heldPatch.awaitEntered()

        val flushScannedSameItem = CompletableDeferred<Unit>()
        val flush = async(Dispatchers.Default) {
            repository.flushPendingProgress(
                scope = scope,
                beforeItemLock = { scannedItem ->
                    if (scannedItem == sameItem) flushScannedSameItem.complete(Unit)
                },
            )
        }
        flushScannedSameItem.await()

        val other = repository.savePushOrEnqueueProgress(
            scope = scope,
            itemId = otherItem,
            currentTime = 20.0,
            isFinished = false,
            duration = 100.0,
            pushToServer = true,
        )

        assertTrue(other.remoteDelivered)
        assertTrue(transport.sawConcurrentPatchPair(RAW_SAME_IDENTITY_ITEM, RAW_OTHER_IDENTITY_ITEM))
        assertEquals(1, transport.patchCount(RAW_SAME_IDENTITY_ITEM))

        heldPatch.release()
        assertTrue(direct.await().remoteDelivered)
        assertTrue(flush.await())
        assertEquals(1, transport.patchCount(RAW_SAME_IDENTITY_ITEM))
    }

    @Test
    fun acknowledgementScopeExpiryAfterDaoDeleteRollsBackAndPreservesCapturedRow() = runBlocking {
        val scopeA = loginAs(OWNER_A, TOKEN_A)
        val itemId = scopeA.encodeIncoming(RAW_ACK_ITEM)
        val rowId = seedPending(scopeA, itemId, currentTime = 10.0, duration = 100.0)
        val daoDeleteReached = CompletableDeferred<Unit>()
        val releaseDaoDelete = CompletableDeferred<Unit>()
        val acknowledgement = async(Dispatchers.Default) {
            repository.acknowledgePendingProgress(
                scope = scopeA,
                itemId = itemId,
                rowIds = listOf(rowId),
                afterDaoDelete = {
                    daoDeleteReached.complete(Unit)
                    releaseDaoDelete.await()
                },
            )
        }

        daoDeleteReached.await()
        loginAs(OWNER_B, TOKEN_B)
        releaseDaoDelete.complete(Unit)

        assertFalse(acknowledgement.await())
        assertEquals(listOf(rowId), database.pendingProgressDao().getForOwnerAndItem(scopeA.ownerKey, itemId).map { it.id })
    }

    @Test
    fun remoteActiveItemTerminalLeaseAndInvalidationUseTheCapturedScopeIdentity() = runBlocking {
        val scope = loginAs(OWNER_A, TOKEN_A)
        val itemId = scope.encodeIncoming(RAW_ACTIVE_IDENTITY_ITEM)
        val imported = PlaybackProgressEntity(itemId, positionSeconds = 20.0, updatedAt = "imported")

        assertTrue(repository.setActiveProgressItem(scope = scope, itemId = itemId))
        assertFalse(
            repository.importServerProgressIfNoPending(
                scope = scope,
                progress = imported,
                importToken = repository.progressImportToken(scope),
                onImported = { error("Active remote playback must block its own import") },
            ),
        )
        assertTrue(repository.clearActiveProgressItemIf(scope = scope, itemId = itemId))

        val terminalLeaseEntered = CompletableDeferred<Unit>()
        val releaseTerminalLease = CompletableDeferred<Unit>()
        val terminalLease = async(Dispatchers.Default) {
            repository.withTerminalProgressOwnership(scope = scope, itemId = itemId) {
                terminalLeaseEntered.complete(Unit)
                releaseTerminalLease.await()
            }
        }
        terminalLeaseEntered.await()
        assertFalse(
            repository.importServerProgressIfNoPending(
                scope = scope,
                progress = imported,
                importToken = repository.progressImportToken(scope),
                onImported = { error("Remote terminal work must block its own import") },
            ),
        )
        releaseTerminalLease.complete(Unit)
        terminalLease.await()

        val beforeInvalidation = requireNotNull(repository.pendingProgressToken(scope, itemId))
        assertTrue(repository.invalidatePendingProgressLifetime(scope, itemId))
        assertNotEquals(beforeInvalidation, repository.pendingProgressToken(scope, itemId))
    }

    @Test
    fun expiredClaimTokenCannotClearAFreshSameOwnerClaim() = runBlocking {
        val originalScope = loginAs(OWNER_A, TOKEN_A)
        val itemId = originalScope.encodeIncoming(RAW_ACTIVE_IDENTITY_ITEM)
        val originalClaim = requireNotNull(repository.claimActiveProgressItem(originalScope, itemId))

        loginAs(OWNER_B, TOKEN_B)
        val freshScope = loginAs(OWNER_A, TOKEN_A_FRESH)
        val freshClaim = requireNotNull(repository.claimActiveProgressItem(freshScope, itemId))

        assertFalse(repository.clearActiveProgressClaim(originalClaim))
        assertTrue(repository.clearActiveProgressClaim(freshClaim))
    }

    @Test
    fun activeClaimCannotPublishAfterItsScopeExpiresDuringCurrentnessCallback() = runBlocking {
        val originalScope = loginAs(OWNER_A, TOKEN_A)
        val itemId = originalScope.encodeIncoming(RAW_ACTIVE_IDENTITY_ITEM)

        val claim = repository.claimActiveProgressItem(originalScope, itemId) {
            loginAs(OWNER_B, TOKEN_B)
            true
        }

        assertNull(claim)
    }

    @Test
    fun apiServicePublicationFenceHoldsAuthMutationUntilSynchronousCommitExits() = runBlocking {
        val scope = loginAs(OWNER_A, TOKEN_A)
        val publicationEntered = CountDownLatch(1)
        val releasePublication = CountDownLatch(1)
        val mutationStarted = CountDownLatch(1)
        val mutationFinished = CountDownLatch(1)
        var committed = false

        val publication = async(Dispatchers.Default) {
            apiService.publishIfCurrentActiveRemoteScope(scope) {
                publicationEntered.countDown()
                releasePublication.await()
                committed = true
            }
        }
        assertTrue(publicationEntered.await(1, TimeUnit.SECONDS))

        val mutation = async(Dispatchers.Default) {
            mutationStarted.countDown()
            apiService.loginWithToken(BASE_URL, TOKEN_B)
            mutationFinished.countDown()
        }
        assertTrue(mutationStarted.await(1, TimeUnit.SECONDS))
        assertFalse(mutationFinished.await(200, TimeUnit.MILLISECONDS))

        releasePublication.countDown()
        publication.await()
        mutation.await()

        assertTrue(committed)
        assertTrue(mutationFinished.count == 0L)
    }

    @Test
    fun playbackManagerSessionEntryPointsDelegateToCoordinatorAndRejectStaleAuth() = runBlocking {
        val originalScope = loginAs(OWNER_A, TOKEN_A)
        val bookId = originalScope.encodeIncoming(RAW_ACTIVE_IDENTITY_ITEM)

        val openFactory = RecordingCoordinatorFactory {
            loginAs(OWNER_B, TOKEN_B)
            loginAs(OWNER_A, TOKEN_A_FRESH)
        }
        val openManager = newPlaybackManager(openFactory)
        val openBook = seedPlaybackManagerState(openManager, originalScope, bookId, currentSessionId = null)

        val openResult = invokePrivateSuspend(
            receiver = openManager,
            methodName = "openServerListeningSession",
            parameterCount = 4,
            openBook,
            4L,
            null,
            originalScope,
        )

        assertEquals(1, openFactory.openCalls)
        assertEquals(false, openResult)
        assertNull(readField(openManager, "currentSession"))
        assertEquals(0, transport.startPlaybackCalls)
        assertEquals(0, transport.closeSessionCalls)

        val recoveryScope = loginAs(OWNER_A, TOKEN_A)
        val recoveryFactory = RecordingCoordinatorFactory {
            loginAs(OWNER_B, TOKEN_B)
            loginAs(OWNER_A, TOKEN_A_FRESH)
        }
        val recoveryManager = newPlaybackManager(recoveryFactory)
        seedPlaybackManagerState(recoveryManager, recoveryScope, bookId, currentSessionId = "old-session")
        val probe = staleSessionProbe(
            requestedGeneration = 4L,
            bookId = bookId,
            sessionId = "old-session",
            position = 42.seconds,
            duration = 100.seconds,
            remoteScope = recoveryScope,
        )

        val recoveryResult = invokePrivateSuspend(
            receiver = recoveryManager,
            methodName = "recoverStaleSession",
            parameterCount = 1,
            probe,
        )

        assertEquals(1, recoveryFactory.recoverCalls)
        assertEquals(false, recoveryResult)
        assertEquals("old-session", (readField(recoveryManager, "currentSession") as PlaybackSessionInfo).id)
        assertEquals(0, transport.startPlaybackCalls)
        assertEquals(0, transport.closeSessionCalls)
    }

    @Test
    fun playbackManagerHonorsFactoryRejectionWithoutFallingThroughToDirectTransport() = runBlocking {
        val scope = loginAs(OWNER_A, TOKEN_A)
        val bookId = scope.encodeIncoming(RAW_ACTIVE_IDENTITY_ITEM)

        val openFactory = RecordingCoordinatorFactory()
        val openManager = newPlaybackManager(openFactory)
        val openBook = seedPlaybackManagerState(openManager, scope, bookId, currentSessionId = null)
        val openResult = invokePrivateSuspend(
            receiver = openManager,
            methodName = "openServerListeningSession",
            parameterCount = 4,
            openBook,
            4L,
            null,
            scope,
        )

        assertEquals(false, openResult)
        assertEquals(1, openFactory.openCalls)
        assertNull(readField(openManager, "currentSession"))
        assertEquals(0, transport.startPlaybackCalls)
        assertEquals(0, transport.closeSessionCalls)

        val recoveryFactory = RecordingCoordinatorFactory()
        val recoveryManager = newPlaybackManager(recoveryFactory)
        seedPlaybackManagerState(recoveryManager, scope, bookId, currentSessionId = "old-session")
        val probe = staleSessionProbe(
            requestedGeneration = 4L,
            bookId = bookId,
            sessionId = "old-session",
            position = 42.seconds,
            duration = 100.seconds,
            remoteScope = scope,
        )
        val recoveryResult = invokePrivateSuspend(
            receiver = recoveryManager,
            methodName = "recoverStaleSession",
            parameterCount = 1,
            probe,
        )

        assertEquals(false, recoveryResult)
        assertEquals(1, recoveryFactory.recoverCalls)
        assertEquals("old-session", (readField(recoveryManager, "currentSession") as PlaybackSessionInfo).id)
        assertEquals(0, transport.startPlaybackCalls)
        assertEquals(0, transport.closeSessionCalls)
    }

    @Test
    fun delayedFailedInitialOpenCannotInvalidateFreshSameBookPendingLifetime() = runBlocking {
        val scope = loginAs(OWNER_A, TOKEN_A)
        val bookId = scope.encodeIncoming(RAW_ACTIVE_IDENTITY_ITEM)
        val oldClaim = requireNotNull(repository.claimPendingLifetime(scope, bookId))
        seedPending(scope, bookId, currentTime = 42.0, duration = 100.0)

        val factory = DelayedRejectingCoordinatorFactory()
        val manager = newPlaybackManager(factory)
        val book = seedPlaybackManagerState(manager, scope, bookId, currentSessionId = null)
        writeField(
            manager,
            "currentPlaybackSource",
            playbackProgressSource(bookId, isLocal = false, remoteScope = scope, pendingLifetimeClaim = oldClaim),
        )

        val open = async(Dispatchers.Default) {
            invokePrivateSuspend(
                receiver = manager,
                methodName = "openServerListeningSession",
                parameterCount = 4,
                book,
                4L,
                null,
                scope,
            )
        }
        factory.entered.await()

        val freshClaim = requireNotNull(repository.claimPendingLifetime(scope, bookId))
        val freshRowToken = requireNotNull(repository.pendingProgressToken(scope, bookId))
        writeField(
            manager,
            "currentPlaybackSource",
            playbackProgressSource(bookId, isLocal = false, remoteScope = scope, pendingLifetimeClaim = freshClaim),
        )

        factory.release.complete(Unit)
        assertEquals(false, open.await())
        assertEquals(1, factory.openCalls)
        assertEquals(freshRowToken, repository.pendingProgressToken(scope, bookId))
        assertTrue(repository.invalidatePendingProgressLifetime(freshClaim))
    }

    @Test
    fun publicationFenceDoesNotAwaitSecureStorageWhileHoldingAuthMutationMutex() = runBlocking {
        val scope = loginAs(OWNER_A, TOKEN_A)
        val settingsTokenMutex = settingsAuthTokenMutex()
        val publicationEntered = CountDownLatch(1)
        val releasePublication = CountDownLatch(1)
        settingsTokenMutex.lock()

        val publication = async(Dispatchers.Default) {
            apiService.publishIfCurrentActiveRemoteScope(scope) {
                publicationEntered.countDown()
                check(releasePublication.await(5, TimeUnit.SECONDS))
                true
            }
        }
        try {
            assertTrue(
                withContext(Dispatchers.IO) {
                    publicationEntered.await(500, TimeUnit.MILLISECONDS)
                },
            )
            val authMutation = async(Dispatchers.Default) {
                apiService.loginWithToken(BASE_URL, TOKEN_A_FRESH)
            }
            assertFalse(
                withTimeoutOrNull(200) {
                    authMutation.await()
                    true
                } ?: false,
            )
            releasePublication.countDown()
            settingsTokenMutex.unlock()
            publication.await()
            authMutation.await()
        } finally {
            releasePublication.countDown()
            if (settingsTokenMutex.isLocked) settingsTokenMutex.unlock()
            publication.cancel()
        }
        Unit
    }

    @Test
    fun realApiServiceScopeFenceAdapterDelegatesPublicationToApiService() = runBlocking {
        val scope = loginAs(OWNER_A, TOKEN_A)
        val fence = ApiServiceRemoteScopePublicationFence(apiService)
        var committed = false

        assertTrue(
            fence.publishIfCurrent(scope) {
                committed = true
                true
            },
        )
        assertTrue(committed)
    }

    @Test
    fun terminalPersistenceFailureClearsItsExactClaimBeforeImportCanProceed() = runBlocking {
        val scope = loginAs(OWNER_A, TOKEN_A)
        val itemId = scope.encodeIncoming(RAW_ACTIVE_IDENTITY_ITEM)
        val claim = requireNotNull(repository.claimActiveProgressItem(scope, itemId))
        val syncManager = newSyncManager()
        var threw = false

        try {
            syncManager.flushPlaybackProgress(
                itemId = itemId,
                currentTime = 42.0,
                isFinished = false,
                duration = 100.0,
                onPersisted = { error("terminal shelf callback failed") },
                isLocal = false,
                remoteScope = scope,
                activeClaim = claim,
            )
        } catch (_: IllegalStateException) {
            threw = true
        }

        assertTrue(threw)
        assertTrue(
            repository.importServerProgressIfNoPending(
                scope = scope,
                progress = PlaybackProgressEntity(itemId, positionSeconds = 42.0, updatedAt = "imported"),
                importToken = repository.progressImportToken(scope),
            ) {},
        )
    }

    @Test
    fun lateOldCleanupCannotInvalidateFreshSameOwnerPendingLifetime() = runBlocking {
        val oldScope = loginAs(OWNER_A, TOKEN_A)
        val itemId = oldScope.encodeIncoming(RAW_ACTIVE_IDENTITY_ITEM)
        val oldClaim = requireNotNull(repository.claimPendingLifetime(oldScope, itemId))

        loginAs(OWNER_B, TOKEN_B)
        val freshScope = loginAs(OWNER_A, TOKEN_A_FRESH)
        val freshClaim = requireNotNull(repository.claimPendingLifetime(freshScope, itemId))
        seedPending(freshScope, itemId, currentTime = 42.0, duration = 100.0)
        val freshRowToken = requireNotNull(repository.pendingProgressToken(freshScope, itemId))

        assertFalse(repository.invalidatePendingProgressLifetime(oldClaim))
        assertEquals(freshRowToken, repository.pendingProgressToken(freshScope, itemId))
        assertTrue(repository.invalidatePendingProgressLifetime(freshClaim))
    }

    @Test
    fun genericProgressReadRejectsRemoteEnvelopeWhileScopedReadRetainsCapturedScope() = runBlocking {
        val scope = loginAs(OWNER_A, TOKEN_A)
        val itemId = scope.encodeIncoming(RAW_GENERIC_READ_ITEM)
        database.playbackProgressDao().upsert(
            PlaybackProgressEntity(itemId, positionSeconds = 15.0, updatedAt = "remote"),
        )

        assertNull(repository.getPlaybackProgress(itemId))
        assertEquals(15.0, requireNotNull(repository.getPlaybackProgress(scope, itemId)).first.inWholeSeconds.toDouble(), 0.0)
    }

    @Test
    fun unscopedApiProgressPatchFailsClosedInsteadOfCapturingCurrentAuth() = runBlocking {
        loginAs(OWNER_A, TOKEN_A)

        assertFalse(apiService.updateProgress("raw-unscoped-item", currentTime = 10.0, duration = 100.0))
        assertEquals(emptyList<String>(), transport.deliveredItemIds)
    }

    private suspend fun loginAs(ownerId: String, token: String): ActiveRemoteScope {
        transport.ownerId = ownerId
        apiService.awaitAuthReady()
        assertTrue(apiService.loginWithToken(BASE_URL, token))
        return requireNotNull(apiService.captureActiveRemoteScope())
    }

    private fun newSyncManager(): SyncManager {
        val audioBookRepository = AudioBookRepository(
            context = fixtureContext,
            audioBookDao = database.audioBookDao(),
            apiService = apiService,
            localListeningSessionDao = database.localListeningSessionDao(),
            localBookmarkDao = database.localBookmarkDao(),
            playbackProgressDao = database.playbackProgressDao(),
        )
        val libraryRepository = LibraryRepository(
            libraryDao = database.libraryDao(),
            audioBookDao = database.audioBookDao(),
            audioBookRepository = audioBookRepository,
            apiService = apiService,
        )
        return SyncManager(
            apiService = apiService,
            libraryRepository = libraryRepository,
            audioBookRepository = audioBookRepository,
            progressRepository = repository,
            audioBookDao = database.audioBookDao(),
            connectivityMonitor = ConnectivityMonitor(
                context = fixtureContext,
                okHttpClient = OkHttpClient(),
                settingsManager = settingsManager,
            ),
            settingsManager = settingsManager,
        )
    }

    private class RecordingCoordinatorFactory(
        private val mutateAuth: suspend () -> Unit = {},
    ) : RemotePlaybackSessionCoordinatorFactory {
        var openCalls: Int = 0
        var recoverCalls: Int = 0

        override suspend fun openServerListeningSession(
            book: AudioBook,
            requestedGeneration: Long,
            loadRequest: Long?,
            remoteScope: ActiveRemoteScope,
        ): Boolean {
            openCalls++
            mutateAuth()
            return false
        }

        override suspend fun recoverStaleSession(probe: StaleSessionProbe): Boolean {
            recoverCalls++
            mutateAuth()
            return false
        }
    }

    private class DelayedRejectingCoordinatorFactory : RemotePlaybackSessionCoordinatorFactory {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var openCalls: Int = 0

        override suspend fun openServerListeningSession(
            book: AudioBook,
            requestedGeneration: Long,
            loadRequest: Long?,
            remoteScope: ActiveRemoteScope,
        ): Boolean {
            openCalls++
            entered.complete(Unit)
            release.await()
            return false
        }

        override suspend fun recoverStaleSession(probe: StaleSessionProbe): Boolean = false
    }

    private fun newPlaybackManager(
        coordinatorFactory: RemotePlaybackSessionCoordinatorFactory,
    ): PlaybackManager {
        val audioBookRepository = AudioBookRepository(
            context = fixtureContext,
            audioBookDao = database.audioBookDao(),
            apiService = apiService,
            localListeningSessionDao = database.localListeningSessionDao(),
            localBookmarkDao = database.localBookmarkDao(),
            playbackProgressDao = database.playbackProgressDao(),
        )
        val libraryRepository = LibraryRepository(
            libraryDao = database.libraryDao(),
            audioBookDao = database.audioBookDao(),
            audioBookRepository = audioBookRepository,
            apiService = apiService,
        )
        val entitlements = EntitlementRepository(
            prefs = EntitlementPrefs(fixtureContext),
            cache = EntitlementCachePrefs(fixtureContext),
            reminderScheduler = WorkManagerTrialReminderScheduler(fixtureContext),
            nowEpochMs = { 1_000L },
        )
        val effectiveSettings = EffectiveSettingsRepository(settingsManager, entitlements)
        val sessionRepository = ListeningSessionRepository(
            sessionDao = database.localListeningSessionDao(),
            apiService = apiService,
            settingsManager = settingsManager,
        )
        val constructor = requireNotNull(
            PlaybackManager::class.java.declaredConstructors.firstOrNull { declared ->
                declared.parameterTypes.any { it == RemotePlaybackSessionCoordinatorFactory::class.java }
            },
        )
        constructor.isAccessible = true
        val args = constructor.parameterTypes.map { parameter ->
            when (parameter) {
                Context::class.java -> fixtureContext
                ApiService::class.java -> apiService
                SettingsManager::class.java -> settingsManager
                EffectiveSettingsRepository::class.java -> effectiveSettings
                ProgressRepository::class.java -> repository
                AudioBookDao::class.java -> database.audioBookDao()
                AudioBookRepository::class.java -> audioBookRepository
                LibraryRepository::class.java -> libraryRepository
                ListeningSessionRepository::class.java -> sessionRepository
                Lazy::class.java -> object : Lazy<SyncManager> {
                    private val value by lazy { newSyncManager() }
                    override fun get(): SyncManager = value
                }
                ConnectivityMonitor::class.java -> ConnectivityMonitor(
                    context = fixtureContext,
                    okHttpClient = OkHttpClient(),
                    settingsManager = settingsManager,
                )
                OkHttpClient::class.java -> OkHttpClient()
                LocalFolderAccess::class.java -> LocalFolderAccess(fixtureContext)
                RemotePlaybackSessionCoordinatorFactory::class.java -> coordinatorFactory
                else -> error("Unmapped PlaybackManager constructor parameter: ${parameter.name}")
            }
        }.toTypedArray()
        return constructor.newInstance(*args) as PlaybackManager
    }

    private fun seedPlaybackManagerState(
        manager: PlaybackManager,
        scope: ActiveRemoteScope,
        bookId: String,
        currentSessionId: String?,
    ): AudioBook {
        val book = AudioBook(
            id = bookId,
            libraryId = scope.encodeIncoming("library-a"),
            isLocal = false,
            title = "Fixture book",
            duration = 100.seconds,
            currentTime = 42.seconds,
        )
        (readField(manager, "_currentBook") as MutableStateFlow<AudioBook?>).value = book
        (readField(manager, "_playbackState") as MutableStateFlow<PlaybackState>).value = PlaybackState.PLAYING
        (readField(manager, "_position") as MutableStateFlow<Duration>).value = 42.seconds
        (readField(manager, "_duration") as MutableStateFlow<Duration>).value = 100.seconds
        writeField(manager, "playbackGeneration", 4L)
        writeField(manager, "currentPlaybackSource", playbackProgressSource(bookId, false, scope))
        writeField(
            manager,
            "currentSession",
            currentSessionId?.let { PlaybackSessionInfo(id = it, itemId = bookId) },
        )
        return book
    }

    private fun readField(receiver: Any, name: String): Any? = receiver.javaClass.getDeclaredField(name).run {
        isAccessible = true
        get(receiver)
    }

    private fun writeField(receiver: Any, name: String, value: Any?) {
        receiver.javaClass.getDeclaredField(name).run {
            isAccessible = true
            set(receiver, value)
        }
    }

    private fun settingsAuthTokenMutex(): Mutex =
        SettingsManager::class.java.getDeclaredField("authTokenMutex").run {
            isAccessible = true
            get(settingsManager) as Mutex
        }

    private suspend fun invokePrivateSuspend(
        receiver: Any,
        methodName: String,
        parameterCount: Int,
        vararg arguments: Any?,
    ): Any? = suspendCoroutineUninterceptedOrReturn { continuation: Continuation<Any?> ->
        val method = receiver.javaClass.declaredMethods.first {
            it.name == methodName && it.parameterCount == parameterCount + 1
        }.also { it.isAccessible = true }
        val invocationArguments = arguments.toMutableList().apply { add(continuation) }.toTypedArray()
        val result = method.invoke(receiver, *invocationArguments)
        if (result === COROUTINE_SUSPENDED) COROUTINE_SUSPENDED else result
    }

    private suspend fun seedPending(
        scope: ActiveRemoteScope,
        itemId: String,
        currentTime: Double,
        duration: Double,
    ): Long = database.pendingProgressDao().insert(
        PendingProgressEntity(
            ownerKey = scope.ownerKey,
            itemId = itemId,
            currentTime = currentTime,
            duration = duration,
            isAtomic = 1,
            timestamp = "2026-09-08T00:00:00Z",
        ),
    )

    private fun book(itemId: String, currentTime: Double, progress: Double) = AudioBookEntity(
        id = itemId,
        libraryId = "fixture-library",
        title = "Fixture book",
        durationSeconds = 100.0,
        currentTimeSeconds = currentTime,
        progress = progress,
    )

    private companion object {
        const val BASE_URL = "https://abs.example.test"
        const val OWNER_A = "owner-a"
        const val OWNER_B = "owner-b"
        const val TOKEN_A = "token-a"
        const val TOKEN_A_FRESH = "token-a-fresh"
        const val TOKEN_B = "token-b"
        const val RAW_IMPORT_ITEM = "import-item"
        const val RAW_VANISHED_ITEM = "vanished-item"
        const val RAW_ZERO_DURATION_ITEM = "zero-duration-item"
        const val RAW_LATER_ITEM = "later-item"
        const val RAW_QUEUED_ONLY_ITEM = "queued-only-item"
        const val RAW_SAME_IDENTITY_ITEM = "same-identity-item"
        const val RAW_OTHER_IDENTITY_ITEM = "other-identity-item"
        const val RAW_ACK_ITEM = "ack-item"
        const val RAW_ACTIVE_IDENTITY_ITEM = "active-identity-item"
        const val RAW_GENERIC_READ_ITEM = "generic-read-item"
    }
}

private class C2RepairTransport {
    @Volatile
    var ownerId: String = ""

    @Volatile
    var startPlaybackCalls: Int = 0

    @Volatile
    var closeSessionCalls: Int = 0

    val deliveredItemIds: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val activePatchItems = mutableSetOf<String>()
    private val concurrentPatchPairs = mutableSetOf<Set<String>>()
    @Volatile
    private var heldPatch: HeldPatch? = null

    fun holdPatch(itemId: String): HeldPatch = HeldPatch(itemId).also { heldPatch = it }

    fun patchCount(itemId: String): Int = synchronized(deliveredItemIds) {
        deliveredItemIds.count { it == itemId }
    }

    fun sawConcurrentPatchPair(first: String, second: String): Boolean = synchronized(activePatchItems) {
        concurrentPatchPairs.contains(setOf(first, second))
    }

    fun api(): AudiobookshelfApi = Proxy.newProxyInstance(
        AudiobookshelfApi::class.java.classLoader,
        arrayOf(AudiobookshelfApi::class.java),
        InvocationHandler { proxy, method, args ->
            when (method.name) {
                "authorize" -> retrofitSuccess(Unit)
                "getMe" -> retrofitSuccess(ApiMeResponse(id = ownerId))
                "startPlaybackSession" -> {
                    startPlaybackCalls++
                    val itemId = args?.get(0) as String
                    retrofitSuccess(ApiPlaybackSession(id = "fixture-session", libraryItemId = itemId, duration = 100.0))
                }
                "closeSession" -> {
                    closeSessionCalls++
                    retrofitSuccess(Unit)
                }
                "updateProgress" -> {
                    val itemId = args?.get(0) as String
                    val request = args[1] as UpdateProgressRequest
                    check(request.currentTime >= 0.0)
                    deliveredItemIds += itemId
                    enterPatch(itemId)
                    try {
                        heldPatch?.takeIf { it.itemId == itemId }?.let { patch ->
                            patch.awaitRelease()
                            if (heldPatch === patch) heldPatch = null
                        }
                        retrofitSuccess(Unit)
                    } finally {
                        leavePatch(itemId)
                    }
                }
                "toString" -> "C2RepairTransport"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.singleOrNull()
                else -> error("Unexpected C2 fixture API call: ${method.name}")
            }
        },
    ) as AudiobookshelfApi

    private fun enterPatch(itemId: String) = synchronized(activePatchItems) {
        activePatchItems.forEach { activeItem -> concurrentPatchPairs += setOf(activeItem, itemId) }
        activePatchItems += itemId
    }

    private fun leavePatch(itemId: String) = synchronized(activePatchItems) {
        activePatchItems -= itemId
    }

    private fun <T> retrofitSuccess(body: T): Response<T> = Response.success(
        body,
        okhttp3.Response.Builder()
            .request(Request.Builder().url("https://abs.example.test").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .build(),
    )
}

private class HeldPatch(
    val itemId: String,
) {
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)

    suspend fun awaitEntered() {
        assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
    }

    fun release() {
        release.countDown()
    }

    fun awaitRelease() {
        entered.countDown()
        check(release.await(5, TimeUnit.SECONDS)) { "Timed out waiting to release held PATCH" }
    }
}

private class C2RepairFixtureContext(
    baseContext: Context,
) : ContextWrapper(baseContext) {
    private val sharedPreferenceNames = linkedSetOf<String>()
    private val fixtureFilesDir = File(baseContext.cacheDir, "c2-repair-1b")

    override fun getApplicationContext(): Context = this

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        sharedPreferenceNames += name
        return baseContext.getSharedPreferences("c2-repair-1b-$name", mode)
    }

    override fun deleteSharedPreferences(name: String): Boolean =
        baseContext.deleteSharedPreferences("c2-repair-1b-$name")

    override fun getFilesDir(): File = fixtureFilesDir.apply { mkdirs() }

    override fun getFileStreamPath(name: String): File = File(filesDir, name)

    override fun deleteFile(name: String): Boolean = getFileStreamPath(name).delete()

    fun clearFixtureStorage() {
        sharedPreferenceNames.forEach(::deleteSharedPreferences)
        deleteFile("NineLivesAudio/settings.json")
        deleteFile("NineLivesAudio")
    }
}
