package com.ninelivesaudio.app.data.remote

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ninelivesaudio.app.domain.model.AppSettings
import com.ninelivesaudio.app.service.SettingsManager
import com.ninelivesaudio.app.service.createPlaybackDataSourceFactory
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Requires the staged TLS fixture through adb reverse at 127.0.0.1:18765.
 * It deliberately builds clients after seeding storage, never after async app
 * settings loading, to prove the process-start persisted opt-in path.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class SelfSignedCertTrustInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun persistedOptInBootstrapsBlankRouteThenFreshOptOutRestoresPlatformTrust() = runBlocking {
        clearSettings()
        val writer = SettingsManager(context)
        writer.saveSettings(AppSettings(allowSelfSignedCertificates = true))

        val coldSettings = SettingsManager(context)
        assertTrue(coldSettings.currentSettings.serverUrl.isBlank())
        val hostAwareCalls = CopyOnWriteArrayList<Pair<SelfSignedCertTrustManager.HostAwareTrustCall, String?>>()
        val service = configuredService(coldSettings) { call, host ->
            hostAwareCalls += call to host
        }
        val activeClient = service.client

        // ApiService.login performs this durable route mutation before making its
        // direct request. The frozen C1b dispatch then owns success or rollback.
        assertEquals(CredentialLoginResult.SUCCESS, service.apiService.login(FIXTURE_URL, "fixture", "password"))
        assertEquals(FIXTURE_URL, coldSettings.currentSettings.serverUrl)
        assertEquals(CredentialLoginResult.UNREACHABLE, service.apiService.login("https://127.0.0.1:1", "fixture", "password"))
        assertEquals(FIXTURE_URL, coldSettings.currentSettings.serverUrl, "failed B login must retain route A")
        assertTrue(hostAwareCalls.any { (_, host) -> host == "127.0.0.1" })
        assertTrue(coldSettings.getTrustedCertificateFingerprint("127.0.0.1") != null)

        val activeResponse = activeClient.newCall(
            Request.Builder().url("$FIXTURE_URL/audio/book-00000.mp3").build(),
        ).execute()
        try {
            coldSettings.updateSettings { it.copy(allowSelfSignedCertificates = false) }
            assertEquals(1, activeResponse.body!!.source().read(ByteArray(1)))
        } finally {
            activeResponse.close()
        }

        val afterRestart = SettingsManager(context)
        val disabledClient = configuredClient(afterRestart)
        try {
            disabledClient.newCall(Request.Builder().url("$FIXTURE_URL/healthcheck").build()).execute().close()
            throw AssertionError("A fresh opt-out client accepted the self-signed fixture")
        } catch (_: IOException) {
            // The fresh client has no custom trust manager and must reject it.
        }

        coldSettings.updateSettings { it.copy(allowSelfSignedCertificates = true) }
        val reenabledClient = configuredClient(SettingsManager(context))
        reenabledClient.newCall(Request.Builder().url("$FIXTURE_URL/healthcheck").build()).execute().use {
            assertEquals(200, it.code)
        }
        val dataSource = createPlaybackDataSourceFactory(context, reenabledClient).createDataSource()
        try {
            dataSource.open(DataSpec.Builder().setUri(FIXTURE_URL.toUri("/audio/book-00000.mp3")).build())
            assertTrue(dataSource.read(ByteArray(1), 0, 1) > 0)
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun changingCommittedRouteDoesNotTrustOldSelfSignedHost() = runBlocking {
        clearSettings()
        SettingsManager(context).saveSettings(AppSettings(allowSelfSignedCertificates = true))
        val settings = SettingsManager(context)
        val client = configuredClient(settings)
        settings.updateSettings { it.copy(serverUrl = "https://unrelated.example.test") }

        try {
            client.newCall(Request.Builder().url("$FIXTURE_URL/healthcheck").build()).execute().close()
            throw AssertionError("An unconfigured host bypassed platform trust")
        } catch (_: IOException) {
            // A→B route changes cannot leave old self-signed hosts trusted.
        }
    }

    private fun configuredClient(
        settings: SettingsManager,
        observer: ((SelfSignedCertTrustManager.HostAwareTrustCall, String?) -> Unit)? = null,
    ): OkHttpClient = with(SelfSignedCertTrustManager) {
        OkHttpClient.Builder().configureSelfSignedCerts(settings, observer).build()
    }

    private fun configuredService(
        settings: SettingsManager,
        observer: (SelfSignedCertTrustManager.HostAwareTrustCall, String?) -> Unit,
    ): ServiceAndClient {
        val auth = AuthInterceptor(settings)
        val client = with(SelfSignedCertTrustManager) {
            OkHttpClient.Builder()
                .addInterceptor(DynamicBaseUrlInterceptor(settings))
                .addNetworkInterceptor(auth)
                .configureSelfSignedCerts(settings, observer)
                .build()
        }
        val api = Retrofit.Builder()
            .baseUrl(DynamicBaseUrlInterceptor.PLACEHOLDER_BASE_URL)
            .client(client)
            .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AudiobookshelfApi::class.java)
        return ServiceAndClient(ApiService(api, auth, settings), client)
    }

    private fun clearSettings() {
        context.deleteSharedPreferences("nine_lives_secure_prefs")
        context.getDir("NineLivesAudio", Context.MODE_PRIVATE).resolve("settings.json").delete()
        assertFalse(SettingsManager(context).currentSettings.allowSelfSignedCertificates)
    }

    private data class ServiceAndClient(
        val apiService: ApiService,
        val client: OkHttpClient,
    )

    private fun String.toUri(path: String) = android.net.Uri.parse(this + path)

    private companion object {
        const val FIXTURE_URL = "https://127.0.0.1:18765"
    }
}
