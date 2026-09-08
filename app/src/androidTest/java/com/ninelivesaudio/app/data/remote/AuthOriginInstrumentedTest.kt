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
        @Volatile var profile: (() -> Unit)? = null
        @Volatile var libraries: (() -> Unit)? = null
        @Volatile var profileRedirectTo: String? = null
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
                        val isProfile = path == "/api/me" || path.endsWith("/api/me")
                        val isLibraries = path == "/api/libraries" || path.endsWith("/api/libraries")
                        val isLogin = path == "/login" || path.endsWith("/login")
                        val isAuthorize = path == "/api/authorize" || path.endsWith("/api/authorize")
                        if (isProfile) profile?.invoke()
                        if (isLibraries) libraries?.invoke()
                        val redirect = if (isProfile) profileRedirectTo.also { profileRedirectTo = null } else null
                        val status = redirect?.let { 302 } ?: when {
                            isLogin -> loginStatus ?: return@thread
                            isAuthorize -> authorize?.invoke() ?: 200
                            else -> 200
                        }
                        val body = when {
                            isLogin -> """{"user":{"id":"$accountId","username":"fixture","token":"$token"}}"""
                            isLibraries -> """{"libraries":[]}"""
                            else -> """{"id":"$accountId","mediaProgress":[],"bookmarks":[]}"""
                        }
                        val location = redirect?.let { "Location: $it\r\n" }.orEmpty()
                        it.getOutputStream().write(("HTTP/1.1 $status Fixture\r\n${location}Content-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body").toByteArray())
                    }
                }
            }
        }
        override fun close() { server.close(); acceptor.join(1_000) }
    }

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NineLivesApp

    private fun clearLegacyRemoteCacheMarker(settings: SettingsManager) {
        val field = SettingsManager::class.java.getDeclaredField("encryptedPrefs" + "$" + "delegate").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val prefs = (field.get(settings) as Lazy<SharedPreferences>).value
        assertTrue(prefs.edit().remove("legacy_remote_cache_state").commit())
    }

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

    @Test fun legacyStoredCredentialStaysUnownedUntilAnExplicitResolverBindsItsAccount() = runBlocking {
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
                val resolved = requireNotNull(api.resolveRemoteTarget())
                assertEquals(a.accountId, resolved.owner.accountId)
                assertEquals(a.accountId, settings.getAuthRecord()?.accountId)
                assertEquals(1, a.requests.count { it.first == "/api/me" })
            }
        } finally {
            app.apiService.logout()
            app.settingsManager.saveSettings(previous)
            if (!previousToken.isNullOrEmpty()) app.apiService.loginWithToken(previous.serverUrl, previousToken)
            app.settingsManager.saveSettings(previous)
        }
    }

    @Test fun redirectedLegacyProfileOutsideFrozenRouteCannotPublishAnOwner() = runBlocking {
        app.apiService.awaitAuthReady()
        val previous = app.settingsManager.currentSettings
        val previousToken = app.settingsManager.getAuthToken()
        try {
            Origin("fixture-redirect").use { origin ->
                val routeA = origin.url + "/abs-a"
                val routeB = origin.url + "/abs-b"
                app.settingsManager.updateSettings { it.copy(serverUrl = routeA) }
                app.settingsManager.saveAuthToken(origin.token, routeA)
                val settings = SettingsManager(InstrumentationRegistry.getInstrumentation().targetContext)
                val auth = AuthInterceptor()
                val client = NetworkModule.provideOkHttpClient(auth, DynamicBaseUrlInterceptor(settings), settings)
                val api = ApiService(
                    NetworkModule.provideAudiobookshelfApi(NetworkModule.provideRetrofit(client, NetworkModule.provideJson())),
                    auth,
                    settings,
                )
                api.initializeFromSettings()
                origin.profileRedirectTo = routeB + "/api/me"

                assertNull(api.resolveRemoteTarget())
                assertTrue(origin.requests.any { it.first == "/abs-a/api/me" && it.second == "Bearer ${origin.token}" })
                assertTrue(origin.requests.any { it.first == "/abs-b/api/me" && it.second == null })
                assertNull(settings.getAuthRecord()?.accountId)
                assertNull(api.captureRemoteTarget())
            }
        } finally {
            app.apiService.logout()
            app.settingsManager.saveSettings(previous)
            if (!previousToken.isNullOrEmpty()) app.apiService.loginWithToken(previous.serverUrl, previousToken)
            app.settingsManager.saveSettings(previous)
        }
    }

    @Test fun staleLegacyProfileAfterAtoBtoARouteCycleCannotPublishAnOwner() = runBlocking {
        app.apiService.awaitAuthReady()
        val previous = app.settingsManager.currentSettings
        val previousToken = app.settingsManager.getAuthToken()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            Origin("fixture-legacy-a").use { a -> Origin("fixture-legacy-b").use { b ->
                app.settingsManager.updateSettings { it.copy(serverUrl = a.url) }
                app.settingsManager.saveAuthToken(a.token, a.url)
                val settings = SettingsManager(InstrumentationRegistry.getInstrumentation().targetContext)
                val auth = AuthInterceptor()
                val client = NetworkModule.provideOkHttpClient(auth, DynamicBaseUrlInterceptor(settings), settings)
                val api = ApiService(NetworkModule.provideAudiobookshelfApi(NetworkModule.provideRetrofit(client, NetworkModule.provideJson())), auth, settings)
                api.initializeFromSettings()
                a.profile = {
                    entered.countDown()
                    check(release.await(15, TimeUnit.SECONDS))
                }
                val resolving = async(Dispatchers.IO) { api.resolveRemoteTarget() }
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                settings.updateSettings { it.copy(serverUrl = b.url) }
                settings.updateSettings { it.copy(serverUrl = a.url) }
                release.countDown()
                assertNull(resolving.await())
                assertNull(settings.getAuthRecord()?.accountId)
            } }
        } finally {
            release.countDown()
            app.apiService.logout()
            app.settingsManager.saveSettings(previous)
            if (!previousToken.isNullOrEmpty()) app.apiService.loginWithToken(previous.serverUrl, previousToken)
            app.settingsManager.saveSettings(previous)
        }
    }

    @Test fun delayedLibraryResponseAfterAtoBtoACannotPublishTheOldScope() = runBlocking {
        app.apiService.awaitAuthReady()
        val previous = app.settingsManager.currentSettings
        val previousToken = app.settingsManager.getAuthToken()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            Origin("fixture-route-a").use { a -> Origin("fixture-route-b").use { b ->
                assertEquals(CredentialLoginResult.SUCCESS, app.apiService.login(a.url, "fixture", "fixture-password"))
                a.libraries = {
                    entered.countDown()
                    check(release.await(15, TimeUnit.SECONDS))
                }
                val loading = async(Dispatchers.IO) { app.apiService.getLibraries() }
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                app.settingsManager.updateSettings { it.copy(serverUrl = b.url) }
                app.settingsManager.updateSettings { it.copy(serverUrl = a.url) }
                release.countDown()
                assertTrue(loading.await() is RemoteResult.Failed)
            } }
        } finally {
            release.countDown()
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

    @Test fun legacyResolverAccountWriteFailureNeverPublishesAnOwner() = runBlocking {
        app.apiService.awaitAuthReady()
        val previous = app.settingsManager.currentSettings
        val previousToken = app.settingsManager.getAuthToken()
        val field = SettingsManager::class.java.getDeclaredField("encryptedPrefs" + "$" + "delegate").apply { isAccessible = true }
        try {
            for (failRollback in listOf(false, true)) {
                Origin("fixture-resolver").use { a ->
                    app.settingsManager.updateSettings { it.copy(serverUrl = a.url) }
                    app.settingsManager.saveAuthToken(a.token, a.url)
                    val settings = SettingsManager(InstrumentationRegistry.getInstrumentation().targetContext)
                    val auth = AuthInterceptor()
                    val client = NetworkModule.provideOkHttpClient(auth, DynamicBaseUrlInterceptor(settings), settings)
                    val api = ApiService(NetworkModule.provideAudiobookshelfApi(NetworkModule.provideRetrofit(client, NetworkModule.provideJson())), auth, settings)
                    api.initializeFromSettings()
                    val originalRecord = settings.getAuthRecord()
                    @Suppress("UNCHECKED_CAST")
                    val original = field.get(settings) as Lazy<SharedPreferences>
                    val backing = original.value
                    val entered = CountDownLatch(0)
                    val release = CountDownLatch(0)
                    val failing = FailingOwnerPreferences(backing, a.accountId, failRollback, entered, release)
                    field.set(settings, lazyOf(failing))
                    try {
                        assertNull(api.resolveRemoteTarget())
                        assertTrue(a.requests.any { it.first == "/api/me" && it.second == "Bearer " + a.token })
                        assertTrue(if (failRollback) failing.failedWrites >= 2 else failing.failedWrites == 1)
                        assertNull(api.captureRemoteTarget())
                        assertNull("Failed resolver owner reached durable preferences", backing.getString("auth_account_id", null))
                        if (!failRollback) assertEquals(originalRecord, settings.getAuthRecord())
                    } finally {
                        field.set(settings, original)
                    }
                }
            }
        } finally {
            app.apiService.logout()
            app.settingsManager.saveSettings(previous)
            if (!previousToken.isNullOrEmpty()) app.apiService.loginWithToken(previous.serverUrl, previousToken)
            app.settingsManager.saveSettings(previous)
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


    /** Forces only the C0 quarantine write to fail without touching route persistence. */
    private class FailingLegacyQuarantinePreferences(
        private val backing: SharedPreferences,
    ) : SharedPreferences by backing {
        override fun edit(): SharedPreferences.Editor {
            val delegate = backing.edit()
            var writesLegacyMarker = false
            return object : SharedPreferences.Editor by delegate {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                    if (key == "legacy_remote_cache_state") writesLegacyMarker = true
                    delegate.putString(key, value)
                    return this
                }

                override fun commit(): Boolean = if (writesLegacyMarker) false else delegate.commit()
            }
        }
    }

    @Test fun restoredLegacyMarkerSurvivesRecreationAndFailedQuarantineKeepsRoute() = runBlocking {
        app.apiService.awaitAuthReady()
        clearLegacyRemoteCacheMarker(app.settingsManager)
        val previous = app.settingsManager.currentSettings
        val previousToken = app.settingsManager.getAuthToken()
        val field = SettingsManager::class.java.getDeclaredField("encryptedPrefs" + "$" + "delegate").apply { isAccessible = true }
        try {
            Origin("fixture-c0-a").use { a -> Origin("fixture-c0-b").use { b ->
                app.settingsManager.updateSettings { it.copy(serverUrl = a.url) }
                app.settingsManager.saveAuthToken(a.token, a.url)

                val firstSettings = SettingsManager(InstrumentationRegistry.getInstrumentation().targetContext)
                val firstAuth = AuthInterceptor()
                val firstClient = NetworkModule.provideOkHttpClient(firstAuth, DynamicBaseUrlInterceptor(firstSettings), firstSettings)
                val firstApi = ApiService(
                    NetworkModule.provideAudiobookshelfApi(NetworkModule.provideRetrofit(firstClient, NetworkModule.provideJson())),
                    firstAuth,
                    firstSettings,
                )
                firstApi.initializeFromSettings()
                assertTrue(firstSettings.getLegacyRemoteCacheState() is LegacyRemoteCacheState.PendingRestoredSession)
                assertEquals(a.accountId, requireNotNull(firstApi.resolveRemoteTarget()).owner.accountId)
                val expected = LegacyRemoteCacheState.PendingRestoredOwner(a.url, a.accountId)
                assertEquals(expected, firstSettings.getLegacyRemoteCacheState())

                val recreatedSettings = SettingsManager(InstrumentationRegistry.getInstrumentation().targetContext)
                val recreatedAuth = AuthInterceptor()
                val recreatedClient = NetworkModule.provideOkHttpClient(recreatedAuth, DynamicBaseUrlInterceptor(recreatedSettings), recreatedSettings)
                val recreatedApi = ApiService(
                    NetworkModule.provideAudiobookshelfApi(NetworkModule.provideRetrofit(recreatedClient, NetworkModule.provideJson())),
                    recreatedAuth,
                    recreatedSettings,
                )
                recreatedApi.initializeFromSettings()
                assertEquals(expected, recreatedSettings.getLegacyRemoteCacheState())

                @Suppress("UNCHECKED_CAST")
                val original = field.get(recreatedSettings) as Lazy<SharedPreferences>
                field.set(recreatedSettings, lazyOf(FailingLegacyQuarantinePreferences(original.value)))
                try {
                    assertEquals(CredentialLoginResult.UNREACHABLE, recreatedApi.login(b.url, "fixture", "fixture-password"))
                    assertEquals(a.url, recreatedSettings.currentSettings.serverUrl)
                    assertEquals(expected, recreatedSettings.getLegacyRemoteCacheState())
                    assertFalse(b.requests.any { it.first == "/login" })
                } finally {
                    field.set(recreatedSettings, original)
                }
            } }
        } finally {
            clearLegacyRemoteCacheMarker(app.settingsManager)
            app.apiService.logout()
            app.settingsManager.saveSettings(previous)
            if (!previousToken.isNullOrEmpty()) app.apiService.loginWithToken(previous.serverUrl, previousToken)
            app.settingsManager.saveSettings(previous)
        }
    }

    @Test fun failedColdLegacySeedKeepsRouteButDoesNotPublishAuth() = runBlocking {
        app.apiService.awaitAuthReady()
        clearLegacyRemoteCacheMarker(app.settingsManager)
        val previous = app.settingsManager.currentSettings
        val previousToken = app.settingsManager.getAuthToken()
        val field = SettingsManager::class.java.getDeclaredField("encryptedPrefs" + "$" + "delegate").apply { isAccessible = true }
        try {
            Origin("fixture-c0-seed-failure").use { a ->
                app.settingsManager.updateSettings { it.copy(serverUrl = a.url) }
                app.settingsManager.saveAuthToken(a.token, a.url)
                val settings = SettingsManager(InstrumentationRegistry.getInstrumentation().targetContext)
                @Suppress("UNCHECKED_CAST")
                val original = field.get(settings) as Lazy<SharedPreferences>
                field.set(settings, lazyOf(FailingLegacyQuarantinePreferences(original.value)))
                try {
                    val auth = AuthInterceptor()
                    val client = NetworkModule.provideOkHttpClient(auth, DynamicBaseUrlInterceptor(settings), settings)
                    val api = ApiService(
                        NetworkModule.provideAudiobookshelfApi(NetworkModule.provideRetrofit(client, NetworkModule.provideJson())),
                        auth,
                        settings,
                    )
                    api.initializeFromSettings()
                    assertEquals(a.url, settings.currentSettings.serverUrl)
                    assertFalse(api.isAuthenticated)
                    assertNull(settings.getLegacyRemoteCacheState())
                } finally {
                    field.set(settings, original)
                }
            }
        } finally {
            clearLegacyRemoteCacheMarker(app.settingsManager)
            app.apiService.logout()
            app.settingsManager.saveSettings(previous)
            if (!previousToken.isNullOrEmpty()) app.apiService.loginWithToken(previous.serverUrl, previousToken)
            app.settingsManager.saveSettings(previous)
        }
    }

    @Test fun sameRouteFreshTokenLoginQuarantinesAnUnclaimedRestoredMarker() = runBlocking {
        app.apiService.awaitAuthReady()
        clearLegacyRemoteCacheMarker(app.settingsManager)
        val previous = app.settingsManager.currentSettings
        val previousToken = app.settingsManager.getAuthToken()
        try {
            Origin("fixture-c0-same-route").use { a ->
                app.settingsManager.updateSettings { it.copy(serverUrl = a.url) }
                app.settingsManager.saveAuthToken(a.token, a.url, a.accountId)
                val restoredSettings = SettingsManager(InstrumentationRegistry.getInstrumentation().targetContext)
                val restoredAuth = AuthInterceptor()
                val restoredClient = NetworkModule.provideOkHttpClient(restoredAuth, DynamicBaseUrlInterceptor(restoredSettings), restoredSettings)
                val restoredApi = ApiService(
                    NetworkModule.provideAudiobookshelfApi(NetworkModule.provideRetrofit(restoredClient, NetworkModule.provideJson())),
                    restoredAuth,
                    restoredSettings,
                )
                restoredApi.initializeFromSettings()
                assertTrue(restoredSettings.getLegacyRemoteCacheState() is LegacyRemoteCacheState.PendingRestoredOwner)

                assertTrue(restoredApi.loginWithToken(a.url, a.token))
                assertEquals(LegacyRemoteCacheState.Quarantined, restoredSettings.getLegacyRemoteCacheState())
                assertTrue(a.requests.any { it.first == "/api/authorize" })
            }
        } finally {
            clearLegacyRemoteCacheMarker(app.settingsManager)
            app.apiService.logout()
            app.settingsManager.saveSettings(previous)
            if (!previousToken.isNullOrEmpty()) app.apiService.loginWithToken(previous.serverUrl, previousToken)
            app.settingsManager.saveSettings(previous)
        }
    }

}
