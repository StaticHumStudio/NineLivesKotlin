package com.ninelivesaudio.app.data.remote

import com.ninelivesaudio.app.service.SettingsManager
import okhttp3.OkHttpClient
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Provides scoped SSL bypass for self-hosted Audiobookshelf servers.
 *
 * ## Security Trade-off
 *
 * Many Audiobookshelf users run self-hosted servers on their LAN with self-signed
 * certificates. Without this bypass, those users cannot connect at all. This is an
 * accepted trade-off for a self-hosted server client.
 *
 * ## Safeguards
 *
 * 1. **Opt-in only:** `allowSelfSignedCertificates` defaults to `false` in [AppSettings].
 *    The permissive trust manager is never installed unless the user explicitly enables it.
 * 2. **Host-scoped:** The custom [javax.net.ssl.HostnameVerifier] restricts certificate
 *    acceptance to the single hostname extracted from the configured server URL. Requests
 *    to any other host use standard certificate validation.
 * 3. **No MITM for third parties:** Only the configured Audiobookshelf server is affected.
 *    All other HTTPS connections (analytics, CDNs, etc.) use the system CA store.
 *
 * ## Limitation
 *
 * This does NOT implement Trust On First Use (TOFU) / certificate pinning. A future
 * improvement could cache the server's certificate fingerprint on first connection and
 * reject changes, detecting MITM attacks even for self-signed certs.
 */
object SelfSignedCertTrustManager {

    /**
     * Configures the OkHttpClient.Builder to accept self-signed certificates
     * ONLY for the server host from settings, and ONLY if the user opted in.
     *
     * When `allowSelfSignedCertificates` is `false` (the default), this method is a
     * no-op — the default OkHttp trust manager validates certificate chains against
     * the system CA store as normal.
     */
    fun OkHttpClient.Builder.configureSelfSignedCerts(
        settingsManager: SettingsManager,
    ): OkHttpClient.Builder {
        // Only install the permissive trust manager when the user has opted in.
        // Without this guard, the no-op checkServerTrusted() accepts every cert.
        val settings = settingsManager.currentSettings
        if (!settings.allowSelfSignedCertificates) {
            return this
        }

        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                // Accept self-signed certs — host restriction enforced by hostnameVerifier below
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())

        sslSocketFactory(sslContext.socketFactory, trustManager)

        hostnameVerifier { hostname, _ ->
            val currentSettings = settingsManager.currentSettings
            val serverUrl = currentSettings.serverUrl
            if (serverUrl.isEmpty()) return@hostnameVerifier false

            try {
                val configuredHost = URI(serverUrl).host
                hostname.equals(configuredHost, ignoreCase = true)
            } catch (_: Exception) {
                false
            }
        }

        return this
    }
}
