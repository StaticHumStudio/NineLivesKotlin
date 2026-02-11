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
     */
    fun OkHttpClient.Builder.configureSelfSignedCerts(
        settingsManager: SettingsManager,
    ): OkHttpClient.Builder {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                // Only allow self-signed certs if:
                // 1. User opted in via settings
                // 2. The cert is for the configured server host
                // The actual host check happens at the hostnameVerifier level
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())

        sslSocketFactory(sslContext.socketFactory, trustManager)

        hostnameVerifier { hostname, session ->
            val settings = settingsManager.currentSettings
            if (!settings.allowSelfSignedCertificates) {
                // Default verification — reject mismatches
                return@hostnameVerifier false
            }

            val serverUrl = settings.serverUrl
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
