package com.ninelivesaudio.app.data.remote

import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ninelivesaudio.app.NineLivesApp
import com.ninelivesaudio.app.di.NetworkModule
import com.ninelivesaudio.app.service.SettingsManager
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Self-contained loopback servers. Run on a disposable test installation. */
@RunWith(AndroidJUnit4::class)
class AuthOriginInstrumentedTest {
    private class Origin(val token: String, val loginStatus: Int? = 200) : Closeable {
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${server.localPort}"
        val requests = CopyOnWriteArrayList<Pair<String, String?>>()
        @Volatile var authorize: (() -> Int)? = null
        @Volatile var accountId: String = "account-$token"
        private val acceptor = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) {
                    socket.use {
                        it.soTimeout = 5_000
                        val reader = it.getInputStream().bufferedReader()
                        val path = reader.readLine()?.split(' ')?.getOrNull(1) ?: return@thread
                        var auth: String? = null
                        var length = 0
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Authorization:", true)) auth = line.substringAfter(':').trim()
                            if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                        }
                        repeat(length) { reader.read() }
                        requests += path to auth
                        val status = when (path) {
                            "/login" -> loginStatus ?: return@thread
                            "/api/authorize" -> authorize?.invoke() ?: 200
                            else -> 200
                        }
                        val body = when (path) {
                            "/login" -> """{"user":{"id":"$accountId","username":"fixture","token":"$token"}}"""
                            "/api/libraries" -> """{"libraries":[]}"""
                            else -> """{"id":"$accountId","mediaProgress":[],"bookmarks":[]}"""
                        }
                        it.getOutputStream().write(("HTTP/1.1 $status Fixture\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body").toByteArray())
                    }
                }
            }
        }
        override fun close() { server.close(); acceptor.join(1_000) }
    }

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NineLivesApp

    @Test fun passwordLoginAndEveryFailureKeepBearersOnTheirOwnOrigins() = runBlocking {
        app.apiService.awaitAuthReady()
        val settings = app.settingsManager.currentSettings
        val token = app.settingsManager.getAuthToken()
        try {
            Origin("fixture-a").use { a ->
                for (status in listOf(200, 401, 503, null)) {
                    assertTrue(app.apiService.loginWithToken(a.url, a.token))
                    Origin("fixture-b", status).use { b ->
                        val result = app.apiService.login(b.url, "fixture", "fixture-password")
                        val loginRequests = b.requests.filter { it.first == "/login" }
                        assertTrue(loginRequests.isNotEmpty())
                        assertTrue("Previous origin bearer disclosed on login", loginRequests.all { it.second == null })
                        if (status == 200) {
                            assertEquals(CredentialLoginResult.SUCCESS, result)
                            app.okHttpClient.newCall(Request.Builder().url(a.url + "/audio/one").build()).execute().close()
                            assertNull("New origin bearer disclosed on old media URL", a.requests.last { it.first == "/audio/one" }.second)
                            app.okHttpClient.newCall(Request.Builder().url(b.url + "/audio/one").build()).execute().close()
                            assertTrue(b.requests.last { it.first == "/audio/one" }.second == "Bearer fixture-b")
                        } else {
                            assertEquals(if (status == 401) CredentialLoginResult.REJECTED else CredentialLoginResult.UNREACHABLE, result)
                            assertEquals(a.url, app.settingsManager.currentSettings.serverUrl)
                            app.okHttpClient.newCall(Request.Builder().url(a.url + "/audio/one").build()).execute().close()
                            assertTrue("Previous origin auth was not restored", a.requests.last { it.first == "/audio/one" }.second == "Bearer fixture-a")
                        }
                    }
                }
            }
        } finally {
            app.apiService.logout()
            app.settingsManager.saveSettings(settings)
            if (!token.isNullOrEmpty()) app.apiService.loginWithToken(settings.serverUrl, token)
            app.settingsManager.saveSettings(settings)
        }
    }

    @Test fun lateInvalidValidationFromAIsDiscardedAfterBLogin() = runBlocking {
        app.apiService.awaitAuthReady()
        val settings = app.settingsManager.currentSettings
        val token = app.settingsManager.getAuthToken()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            Origin("fixture-a").use { a -> Origin("fixture-b").use { b ->
                assertTrue(app.apiService.loginWithToken(a.url, a.token))
                a.authorize = {
                    entered.countDown()
                    check(release.await(15, TimeUnit.SECONDS))
                    401
                }
                val stale = async(Dispatchers.IO) { app.apiService.validateStoredTokenSession(forceRefresh = true) }
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                assertEquals(CredentialLoginResult.SUCCESS, app.apiService.login(b.url, "fixture", "fixture-password"))
                release.countDown()
                assertNull(stale.await())
                assertEquals(b.url, app.settingsManager.currentSettings.serverUrl)
                assertTrue(app.apiService.validateToken())
                assertTrue(b.requests.any { it.first == "/api/authorize" && it.second == "Bearer fixture-b" })
            } }
        } finally {
            release.countDown()
            app.apiService.logout()
            app.settingsManager.saveSettings(settings)
            if (!token.isNullOrEmpty()) app.apiService.loginWithToken(settings.serverUrl, token)
            app.settingsManager.saveSettings(settings)
        }
    }
    @Test fun restartAfterFailedSettingsRollbackKeepsStoredTokenBoundToA() = runBlocking {
        app.apiService.awaitAuthReady()
        val previous = app.settingsManager.currentSettings
        val previousToken = app.settingsManager.getAuthToken()
        try {
            Origin("fixture-a").use { a -> Origin("fixture-b").use { b ->
                assertTrue(app.apiService.loginWithToken(a.url, a.token))
                // Persist exactly the state left when a B login and its settings
                // rollback fail: A token retained, settings still routed to B.
                app.settingsManager.updateSettings { it.copy(serverUrl = b.url) }
                val settings = SettingsManager(InstrumentationRegistry.getInstrumentation().targetContext)
                val auth = AuthInterceptor()
                val client = NetworkModule.provideOkHttpClient(auth, DynamicBaseUrlInterceptor(settings), settings)
                val api = ApiService(NetworkModule.provideAudiobookshelfApi(NetworkModule.provideRetrofit(client, NetworkModule.provideJson())), auth, settings)
                api.initializeFromSettings()
                client.newCall(Request.Builder().url(b.url + "/after-restart").build()).execute().close()
                assertTrue(b.requests.any { it.first == "/after-restart" })
                assertNull("Restart rebound the retained token to the attempted server", b.requests.last { it.first == "/after-restart" }.second)
                assertTrue(settings.getAuthToken() == a.token)
                assertFalse(api.isAuthenticated)
            } }
        } finally {
            app.apiService.logout()
            app.settingsManager.saveSettings(previous)
            if (!previousToken.isNullOrEmpty()) app.apiService.loginWithToken(previous.serverUrl, previousToken)
            app.settingsManager.saveSettings(previous)
        }
    }

    @Test fun acceptedOwnerRestoresWithoutProfileAndTracksAccountChanges() = runBlocking {
        app.apiService.awaitAuthReady()
        val previous = app.settingsManager.currentSettings
        val previousToken = app.settingsManager.getAuthToken()
        try {
            Origin("fixture-owner").use { a ->
                assertEquals(CredentialLoginResult.SUCCESS, app.apiService.login(a.url, "fixture", "fixture-password"))
                val first = requireNotNull(app.apiService.captureRemoteTarget())
                assertEquals(a.accountId, first.owner.accountId)
                assertEquals(a.accountId, app.settingsManager.getAuthRecord()?.accountId)
                val profileCount = a.requests.count { it.first == "/api/me" }
                val settings = SettingsManager(InstrumentationRegistry.getInstrumentation().targetContext)
                val auth = AuthInterceptor()
                val client = NetworkModule.provideOkHttpClient(auth, DynamicBaseUrlInterceptor(settings), settings)
                val restored = ApiService(NetworkModule.provideAudiobookshelfApi(NetworkModule.provideRetrofit(client, NetworkModule.provideJson())), auth, settings)
                restored.initializeFromSettings()
                assertEquals(first.owner, restored.captureRemoteTarget()?.owner)
                assertEquals("Cold owner restore fetched a heavy profile", profileCount, a.requests.count { it.first == "/api/me" })

                app.settingsManager.updateSettings { it.copy(serverUrl = a.url + "/other") }
                assertFalse(app.apiService.isCurrentRemoteTarget(first))
                app.settingsManager.updateSettings { it.copy(serverUrl = a.url) }
                a.accountId = "second-account"
                assertEquals(CredentialLoginResult.SUCCESS, app.apiService.login(a.url, "second", "fixture-password"))
                val second = requireNotNull(app.apiService.captureRemoteTarget())
                assertNotEquals(first.owner.key, second.owner.key)
                assertFalse(app.apiService.isCurrentRemoteTarget(first))
                a.accountId = first.owner.accountId
                assertEquals(CredentialLoginResult.SUCCESS, app.apiService.login(a.url, "fixture", "fixture-password"))
                assertEquals(first.owner.key, app.apiService.captureRemoteTarget()?.owner?.key)
                assertFalse("An old A operation survived A to B to A", app.apiService.isCurrentRemoteTarget(first))
            }
        } finally {
            app.apiService.logout()
            app.settingsManager.saveSettings(previous)
            if (!previousToken.isNullOrEmpty()) app.apiService.loginWithToken(previous.serverUrl, previousToken)
            app.settingsManager.saveSettings(previous)
        }
    }

    @Test fun tokenProfileBindingFlushRollbackAndClearPreserveCompleteOwnerRecord() = runBlocking {
        app.apiService.awaitAuthReady()
        val previous = app.settingsManager.currentSettings
        val previousToken = app.settingsManager.getAuthToken()
        try {
            Origin("fixture-owner").use { a ->
                assertTrue(app.apiService.loginWithToken(a.url, a.token))
                val before = app.settingsManager.getAuthRecord()
                assertEquals(a.accountId, before?.accountId)
                assertTrue(a.requests.any { it.first == "/api/me" && it.second == "Bearer " + a.token })
                app.settingsManager.persistAuthTokenServerBinding(a.token, "http://ignored.invalid")
                assertEquals(before, app.settingsManager.getAuthRecord())
                Origin("fixture-rejected", 401).use { b ->
                    assertEquals(CredentialLoginResult.REJECTED, app.apiService.login(b.url, "rejected", "fixture-password"))
                }
                assertEquals(before, app.settingsManager.getAuthRecord())
                assertEquals(a.accountId, app.apiService.captureRemoteTarget()?.owner?.accountId)
                app.settingsManager.saveAuthToken("temporary-fixture", a.url, "other-account")
                assertNull("External record change retained old owner target", app.apiService.captureRemoteTarget())
                assertFalse(app.settingsManager.replaceAuthTokenIfCurrent("wrong-fixture", a.token, a.url, a.accountId))
                assertTrue(app.settingsManager.replaceAuthTokenIfCurrent("temporary-fixture", a.token, a.url, a.accountId))
                assertEquals(before, app.settingsManager.getAuthRecord())
                app.apiService.logout()
                assertNull(app.settingsManager.getAuthRecord())
                assertNull(app.apiService.captureRemoteTarget())
            }
        } finally {
            app.apiService.logout()
            app.settingsManager.saveSettings(previous)
            if (!previousToken.isNullOrEmpty()) app.apiService.loginWithToken(previous.serverUrl, previousToken)
            app.settingsManager.saveSettings(previous)
        }
    }

    @Test fun legacyStoredCredentialStaysUnownedWithoutAnAutomaticProfileRequest() = runBlocking {
        app.apiService.awaitAuthReady()
        val previous = app.settingsManager.currentSettings
        val previousToken = app.settingsManager.getAuthToken()
        try {
            Origin("fixture-legacy").use { a ->
                app.settingsManager.updateSettings { it.copy(serverUrl = a.url) }
                app.settingsManager.saveAuthToken(a.token, a.url)
                val settings = SettingsManager(InstrumentationRegistry.getInstrumentation().targetContext)
                val auth = AuthInterceptor()
                val client = NetworkModule.provideOkHttpClient(auth, DynamicBaseUrlInterceptor(settings), settings)
                val api = ApiService(NetworkModule.provideAudiobookshelfApi(NetworkModule.provideRetrofit(client, NetworkModule.provideJson())), auth, settings)
                api.initializeFromSettings()
                assertTrue(api.isAuthenticated)
                assertNull(api.captureRemoteTarget())
                assertFalse("Startup fetched a full profile: ${a.requests.map { it.first }}", a.requests.any { it.first == "/api/me" })
                assertNull(settings.getAuthRecord()?.accountId)
            }
        } finally {
            app.apiService.logout()
            app.settingsManager.saveSettings(previous)
            if (!previousToken.isNullOrEmpty()) app.apiService.loginWithToken(previous.serverUrl, previousToken)
            app.settingsManager.saveSettings(previous)
        }
    }

    /** Emulates commit(false) changing the memory map without reaching durable storage. */
    private class FailingOwnerPreferences(
        private val backing: SharedPreferences,
        private val failingAccount: String,
        private val failRollback: Boolean,
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
    ) : SharedPreferences by backing {
        private val overlay = mutableMapOf<String, String?>()
        @Volatile var failedWrites = 0
            private set

        override fun getString(key: String?, defValue: String?): String? = synchronized(overlay) {
            if (overlay.containsKey(key)) overlay[key] ?: defValue else backing.getString(key, defValue)
        }

        override fun edit(): SharedPreferences.Editor {
            val delegate = backing.edit()
            val writes = mutableMapOf<String, String?>()
            return object : SharedPreferences.Editor by delegate {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                    writes[requireNotNull(key)] = value
                    delegate.putString(key, value)
                    return this
                }
                override fun remove(key: String?): SharedPreferences.Editor {
                    writes[requireNotNull(key)] = null
                    delegate.remove(key)
                    return this
                }
                override fun commit(): Boolean {
                    val ownerWrite = failedWrites == 0 && writes["auth_account_id"] == failingAccount
                    if (ownerWrite) {
                        entered.countDown()
                        check(release.await(15, TimeUnit.SECONDS))
                    }
                    if (ownerWrite || (failedWrites > 0 && failRollback)) {
                        synchronized(overlay) { overlay.putAll(writes) }
                        failedWrites++
                        return false
                    }
                    // A later successful commit flushes the current memory map,
                    // including prior failed writes, just like SharedPreferences.
                    synchronized(overlay) {
                        for ((key, value) in overlay) {
                            if (key !in writes) {
                                if (value == null) delegate.remove(key) else delegate.putString(key, value)
                            }
                        }
                        val success = delegate.commit()
                        if (success) overlay.clear()
                        return success
                    }
                }
            }
        }
    }

    @Test fun failedAccountCommitSuppressesOwnerUntilFullRollbackSucceeds() = runBlocking {
        app.apiService.awaitAuthReady()
        val previous = app.settingsManager.currentSettings
        val previousToken = app.settingsManager.getAuthToken()
        val field = SettingsManager::class.java.getDeclaredField("encryptedPrefs" + "$" + "delegate").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val original = field.get(app.settingsManager) as Lazy<SharedPreferences>
        val backing = original.value
        try {
            for (failRollback in listOf(false, true)) {
                Origin("fixture-owner-a").use { a -> Origin("fixture-owner-b").use { b ->
                    assertTrue(app.apiService.loginWithToken(a.url, a.token))
                    val previousRecord = app.settingsManager.getAuthRecord()
                    val previousTarget = requireNotNull(app.apiService.captureRemoteTarget())
                    val entered = CountDownLatch(1)
                    val release = CountDownLatch(1)
                    val failing = FailingOwnerPreferences(backing, b.accountId, failRollback, entered, release)
                    field.set(app.settingsManager, lazyOf(failing))
                    try {
                        val login = async(Dispatchers.IO) { app.apiService.loginWithToken(b.url, b.token) }
                        assertTrue("Account-bearing commit was not reached", entered.await(10, TimeUnit.SECONDS))
                        val target = async(Dispatchers.IO) { app.apiService.captureRemoteTarget() }
                        delay(100)
                        assertFalse("Owner escaped an unresolved auth mutation", target.isCompleted)
                        release.countDown()
                        assertFalse(login.await())
                        val after = target.await()
                        if (failRollback) {
                            assertTrue(failing.failedWrites >= 2)
                            assertNull(after)
                            assertNull(app.apiService.captureRemoteTarget())
                            assertNull("Failed owner reached durable preferences", backing.getString("auth_account_id", null))
                        } else {
                            assertEquals(1, failing.failedWrites)
                            assertEquals(previousRecord, app.settingsManager.getAuthRecord())
                            assertEquals(previousTarget.owner, after?.owner)
                            assertEquals(a.accountId, backing.getString("auth_account_id", null))
                        }
                    } finally {
                        release.countDown()
                        field.set(app.settingsManager, original)
                    }
                } }
            }
        } finally {
            field.set(app.settingsManager, original)
            app.apiService.logout()
            app.settingsManager.saveSettings(previous)
            if (!previousToken.isNullOrEmpty()) app.apiService.loginWithToken(previous.serverUrl, previousToken)
            app.settingsManager.saveSettings(previous)
        }
    }

}
