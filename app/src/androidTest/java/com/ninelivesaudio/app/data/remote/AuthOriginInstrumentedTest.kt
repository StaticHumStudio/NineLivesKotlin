package com.ninelivesaudio.app.data.remote

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
                            "/login" -> """{"user":{"id":"account-$token","username":"fixture","token":"$token"}}"""
                            "/api/libraries" -> """{"libraries":[]}"""
                            else -> """{"id":"account-$token","mediaProgress":[],"bookmarks":[]}"""
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

}
