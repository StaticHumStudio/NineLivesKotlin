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
 * Provides scoped SSL bypass — only trusts self-signed certs for the configured server host.
 * Matches the C# ServerCertificateCustomValidationCallback logic.
 */
object SelfSignedCertTrustManager {

    /**
     * Configures the OkHttpClient.Builder to accept self-signed certificates
     * ONLY for the server host from settings, and ONLY if the user opted in.
     *
     * When allowSelfSignedCertificates is false, the default OkHttp trust manager
     * is used — this properly validates certificate chains against the system CA store.
     * The old implementation installed a no-op trust manager unconditionally, which
     * accepted ALL certificates for ALL hosts regardless of the setting.
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
